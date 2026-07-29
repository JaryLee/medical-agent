package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
import com.jarylee.medicalagent.literature.LiteratureValidationModels.CitationValidation;
import com.jarylee.medicalagent.literature.LiteratureValidationModels.ValidationResult;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchConcept;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisModels.AnalysisResult;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisModels.DimensionMatch;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisModels.ResearchGap;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisModels.SimilarResearch;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SimilarResearchAnalysisService {
    public static final String RESULT_SCHEMA_VERSION = "similar-research-analysis-result/v1";
    public static final String ALGORITHM_VERSION = "deterministic-peco-overlap/v1";
    private static final String NO_HIGHLY_SIMILAR_CONCLUSION =
            "基于当前检索数据库、检索式和检索日期，暂未发现高度相似研究；"
                    + "该结论不代表完成了全部数据库和灰色文献检索。";
    private static final Pattern FIELD_TAG = Pattern.compile("\\[[^]]+]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Map<String, Integer> WEIGHTS = Map.of(
            "POPULATION", 20,
            "EXPOSURE", 25,
            "COMPARATOR", 10,
            "OUTCOME", 30,
            "STUDY_DESIGN", 15);

    private final SimilarResearchAnalysisRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public SimilarResearchAnalysisService(
            SimilarResearchAnalysisRepository repository,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    public AnalysisResult execute(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            PecoDefinition peco,
            SearchStrategy strategy,
            PubMedSearchModels.SearchResult pubmed,
            ClinicalTrialsSearchModels.SearchResult clinicalTrials,
            ValidationResult validation) {
        requireInputs(peco, strategy, pubmed, clinicalTrials, validation);
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("peco", peco);
        inputSnapshot.put("searchStrategy", strategy);
        inputSnapshot.put("pubmedSearch", pubmed);
        inputSnapshot.put("clinicalTrialsSearch", clinicalTrials);
        inputSnapshot.put("literatureValidation", validation);
        byte[] input = writeBytes(inputSnapshot);
        String inputHash = sha256(input);
        UUID analysisId = UUID.randomUUID();
        Instant started = clock.instant();
        List<String> databaseScope = List.of("PUBMED", "CLINICAL_TRIALS_GOV", "CROSSREF");
        repository.create(new SimilarResearchAnalysisRepository.AnalysisData(
                analysisId, hospitalId, projectId, agentTaskId, "RUNNING",
                started, null, null, null, null, null, null, null,
                inputHash, ALGORITHM_VERSION, write(databaseScope), null,
                null, null, null));
        try {
            Map<String, List<String>> terms = dimensionTerms(peco, strategy);
            Map<String, CitationValidation> validationByPmid = new HashMap<>();
            validation.citations().forEach(value ->
                    validationByPmid.put(value.pmid(), value));
            List<SimilarResearch> comparisons = new ArrayList<>();
            int excludedCitations = 0;
            for (var article : pubmed.records()) {
                CitationValidation citation = validationByPmid.get(article.pmid());
                if (citation == null || !acceptedCitation(citation.status())) {
                    excludedCitations++;
                    continue;
                }
                comparisons.add(compareArticle(
                        article, citation.status(), validation, terms));
            }
            for (var trial : clinicalTrials.records()) {
                comparisons.add(compareTrial(trial, terms));
            }
            comparisons.sort(Comparator
                    .comparingInt(SimilarResearch::similarityScore).reversed()
                    .thenComparing(SimilarResearch::sourceIdentifier));
            List<ResearchGap> gaps = gaps(comparisons);
            int high = countTier(comparisons, "HIGH");
            int moderate = countTier(comparisons, "MODERATE");
            int low = countTier(comparisons, "LOW");
            String conclusion = high == 0
                    ? NO_HIGHLY_SIMILAR_CONCLUSION
                    : "当前检索范围内发现 " + high
                    + " 项高度相似来源，需要由医学与方法学专家复核差异；"
                    + "该结果不构成创新性或优先权证明，也不代表完成了全部数据库和灰色文献检索。";
            Instant completed = clock.instant();
            AnalysisResult result = new AnalysisResult(
                    RESULT_SCHEMA_VERSION, analysisId, completed,
                    peco.researchQuestion(), databaseScope, comparisons.size(),
                    excludedCitations, high, moderate, low, List.copyOf(comparisons),
                    gaps, conclusion, inputHash, ALGORITHM_VERSION,
                    List.of(
                            "相似度是版本化关键词维度匹配结果，不是语义等价、系统综述结论或创新性评分",
                            "PubMed 仅分析 STEP10 中 VERIFIED 或 VERIFIED_WITH_METADATA_DIFFERENCES 的引文",
                            "ClinicalTrials.gov 来源保持注册表证据属性，不等同于同行评议发表证据",
                            "当前未覆盖 CNKI、万方、维普、Embase、Web of Science 及灰色文献",
                            "摘要级证据不能替代全文审阅，研究空白建议必须由医学、统计和科研管理专家确认"));
            repository.complete(
                    new SimilarResearchAnalysisRepository.AnalysisData(
                            analysisId, hospitalId, projectId, agentTaskId,
                            "COMPLETED", started, completed, comparisons.size(),
                            excludedCitations, high, moderate, low, gaps.size(),
                            inputHash, ALGORITHM_VERSION, write(databaseScope),
                            conclusion, write(result), null, null),
                    comparisons, gaps);
            return result;
        } catch (RuntimeException exception) {
            repository.fail(hospitalId, analysisId, errorCode(exception),
                    safeMessage(exception), clock.instant());
            if (exception instanceof SimilarResearchAnalysisException) throw exception;
            throw new SimilarResearchAnalysisException(
                    "SIMILAR_RESEARCH_ANALYSIS_FAILED",
                    "相似研究分析执行失败", exception);
        }
    }

    private SimilarResearch compareArticle(
            PubMedSearchModels.Article article,
            String verificationStatus,
            ValidationResult validation,
            Map<String, List<String>> terms) {
        String sourceText = String.join(" ", safe(article.title()),
                safe(article.abstractText()), safe(article.journal()),
                join(article.authors()));
        List<String> linked = validation.evidenceLinks().stream()
                .filter(link -> article.pmid().equals(link.pmid()))
                .map(LiteratureValidationModels.EvidenceLink::nctId)
                .distinct().sorted().toList();
        return comparison(
                "PUBMED_ARTICLE", article.pmid(), article.pmid(), article.doi(),
                null, article.title(), article.publicationDate(), verificationStatus,
                article.evidenceScope(), sourceText, terms, linked);
    }

    private SimilarResearch compareTrial(
            ClinicalTrialsSearchModels.Trial trial,
            Map<String, List<String>> terms) {
        String sourceText = String.join(" ", safe(trial.briefTitle()),
                safe(trial.officialTitle()), join(trial.conditions()),
                join(trial.interventions()), safe(trial.briefSummary()),
                join(trial.primaryOutcomes()), join(trial.phases()),
                safe(trial.studyType()));
        List<String> linked = trial.linkedPmids() == null ? List.of()
                : trial.linkedPmids().stream().map(value -> "PMID:" + value).toList();
        return comparison(
                "TRIAL_REGISTRY", trial.nctId(), null, null, trial.nctId(),
                trial.briefTitle(), trial.completionDate(), "REGISTRY_VERIFIED",
                trial.evidenceScope(), sourceText, terms, linked);
    }

    private SimilarResearch comparison(
            String sourceType,
            String sourceIdentifier,
            String pmid,
            String doi,
            String nctId,
            String title,
            String sourceDate,
            String verificationStatus,
            String evidenceScope,
            String sourceText,
            Map<String, List<String>> terms,
            List<String> linked) {
        String normalizedSource = normalize(sourceText);
        List<DimensionMatch> dimensions = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        int score = 0;
        for (var entry : WEIGHTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            String dimension = entry.getKey();
            List<String> matchedTerms = terms.getOrDefault(dimension, List.of()).stream()
                    .filter(term -> containsTerm(normalizedSource, term))
                    .distinct().toList();
            boolean matched = !matchedTerms.isEmpty();
            if (matched) score += entry.getValue();
            else differences.add(differenceLabel(dimension) + "未在当前来源元数据/摘要中匹配");
            dimensions.add(new DimensionMatch(
                    dimension, matched, entry.getValue(), matchedTerms));
        }
        String tier = score >= 80 ? "HIGH" : score >= 50 ? "MODERATE" : "LOW";
        return new SimilarResearch(
                sourceType, sourceIdentifier, pmid, doi, nctId, title, sourceDate,
                score, tier, verificationStatus, evidenceScope,
                List.copyOf(dimensions), List.copyOf(differences), List.copyOf(linked));
    }

    private List<ResearchGap> gaps(List<SimilarResearch> comparisons) {
        List<String> sources = comparisons.stream()
                .map(SimilarResearch::sourceIdentifier).toList();
        List<ResearchGap> result = new ArrayList<>();
        for (String dimension : List.of(
                "POPULATION", "EXPOSURE", "COMPARATOR", "OUTCOME", "STUDY_DESIGN")) {
            boolean anyMatch = comparisons.stream()
                    .flatMap(value -> value.dimensions().stream())
                    .anyMatch(value -> dimension.equals(value.dimension()) && value.matched());
            if (!anyMatch) {
                result.add(new ResearchGap(
                        dimension + "_EVIDENCE_GAP",
                        "当前已验证检索结果中未发现明确匹配"
                                + differenceLabel(dimension) + "的来源，建议扩展检索并人工复核。",
                        "仅依据当前 PubMed、ClinicalTrials.gov、Crossref "
                                + "检索与元数据/摘要级内容形成，不能据此证明创新。",
                        sources));
            }
        }
        boolean unresolved = comparisons.stream()
                .filter(value -> "TRIAL_REGISTRY".equals(value.sourceType()))
                .anyMatch(value -> value.linkedSourceIdentifiers().isEmpty());
        if (unresolved) {
            result.add(new ResearchGap(
                    "REGISTRY_PUBLICATION_LINK_GAP",
                    "部分注册研究未在当前记录中提供可解析的 PMID，建议核查结果发表及其他出版物。",
                    "依据 ClinicalTrials.gov 当前公开关联 PMID 状态。",
                    comparisons.stream()
                            .filter(value -> "TRIAL_REGISTRY".equals(value.sourceType())
                                    && value.linkedSourceIdentifiers().isEmpty())
                            .map(SimilarResearch::sourceIdentifier).toList()));
        }
        return List.copyOf(result);
    }

    private Map<String, List<String>> dimensionTerms(
            PecoDefinition peco, SearchStrategy strategy) {
        Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
        strategy.concepts().forEach(concept ->
                addTerms(values, concept.code(), concept.terms()));
        addTerms(values, "POPULATION", optionalTerm(peco.population()));
        addTerms(values, "EXPOSURE", optionalTerm(peco.exposure()));
        addTerms(values, "COMPARATOR", optionalTerm(peco.comparator()));
        addTerms(values, "OUTCOME", optionalTerm(peco.outcome()));
        addAliases(values);
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, terms) -> result.put(key, List.copyOf(terms)));
        return result;
    }

    private void addTerms(
            Map<String, LinkedHashSet<String>> values,
            String dimension,
            List<String> rawTerms) {
        if (!WEIGHTS.containsKey(dimension) || rawTerms == null) return;
        LinkedHashSet<String> target =
                values.computeIfAbsent(dimension, ignored -> new LinkedHashSet<>());
        rawTerms.stream().filter(value -> value != null && !value.isBlank())
                .map(this::normalizeTerm).filter(value -> !value.isBlank())
                .forEach(target::add);
    }

    private List<String> optionalTerm(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private void addAliases(Map<String, LinkedHashSet<String>> values) {
        String all = values.values().stream().flatMap(Set::stream)
                .reduce("", (left, right) -> left + " " + right);
        if (containsAny(all, "糖尿病", "diabetes", "t2dm")) {
            addAlias(values, "POPULATION", "diabetes", "type 2 diabetes", "t2dm");
        }
        if (containsAny(all, "sglt2", "降糖药", "empagliflozin", "dapagliflozin")) {
            addAlias(values, "EXPOSURE", "sglt2", "empagliflozin",
                    "dapagliflozin", "canagliflozin");
        }
        if (containsAny(all, "肾", "egfr", "肌酐", "kidney", "renal")) {
            addAlias(values, "OUTCOME", "kidney", "renal", "egfr", "creatinine");
        }
        if (containsAny(all, "未暴露", "对照", "placebo", "control")) {
            addAlias(values, "COMPARATOR", "placebo", "control", "unexposed");
        }
        String design = values.getOrDefault(
                "STUDY_DESIGN", new LinkedHashSet<>()).stream()
                .reduce("", (left, right) -> left + " " + right);
        if (design.contains("cohort")) {
            addAlias(values, "STUDY_DESIGN", "cohort", "longitudinal", "observational");
        } else if (design.contains("cross sectional")) {
            addAlias(values, "STUDY_DESIGN", "cross sectional", "observational");
        } else if (design.contains("case control")) {
            addAlias(values, "STUDY_DESIGN", "case control", "observational");
        }
    }

    private void addAlias(
            Map<String, LinkedHashSet<String>> values,
            String dimension,
            String... aliases) {
        var target = values.computeIfAbsent(dimension, ignored -> new LinkedHashSet<>());
        for (String alias : aliases) target.add(alias);
    }

    private boolean containsTerm(String source, String term) {
        String normalized = normalizeTerm(term);
        return normalized.length() >= 3 && source.contains(normalized);
    }

    private String normalizeTerm(String value) {
        return normalize(FIELD_TAG.matcher(value).replaceAll(" ")
                .replace("\"", " "));
    }

    private String normalize(String value) {
        if (value == null) return "";
        return NON_ALNUM.matcher(value.toLowerCase(Locale.ROOT))
                .replaceAll(" ").strip();
    }

    private String differenceLabel(String dimension) {
        return switch (dimension) {
            case "POPULATION" -> "研究对象";
            case "EXPOSURE" -> "暴露/干预";
            case "COMPARATOR" -> "对照";
            case "OUTCOME" -> "结局";
            case "STUDY_DESIGN" -> "研究设计";
            default -> dimension;
        };
    }

    private boolean acceptedCitation(String status) {
        return "VERIFIED".equals(status)
                || "VERIFIED_WITH_METADATA_DIFFERENCES".equals(status);
    }

    private int countTier(List<SimilarResearch> values, String tier) {
        return (int) values.stream()
                .filter(value -> tier.equals(value.similarityTier())).count();
    }

    private void requireInputs(
            PecoDefinition peco,
            SearchStrategy strategy,
            PubMedSearchModels.SearchResult pubmed,
            ClinicalTrialsSearchModels.SearchResult clinicalTrials,
            ValidationResult validation) {
        if (peco == null || peco.researchQuestion() == null
                || peco.researchQuestion().isBlank()) {
            throw new IllegalArgumentException("缺少已确认研究问题");
        }
        if (strategy == null || !"CONFIRMED".equals(strategy.confirmationStatus())
                || strategy.concepts() == null) {
            throw new IllegalArgumentException("缺少已确认检索策略");
        }
        if (pubmed == null || pubmed.records() == null
                || clinicalTrials == null || clinicalTrials.records() == null
                || validation == null || validation.citations() == null
                || validation.evidenceLinks() == null) {
            throw new IllegalArgumentException("缺少 STEP08 至 STEP10 的结构化结果");
        }
    }

    private byte[] writeBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new SimilarResearchAnalysisException(
                    "SIMILAR_RESEARCH_INPUT_INVALID",
                    "相似研究分析输入序列化失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new SimilarResearchAnalysisException(
                    "SIMILAR_RESEARCH_OUTPUT_INVALID",
                    "相似研究分析结果序列化失败", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("相似研究分析输入哈希失败", exception);
        }
    }

    private String join(List<String> values) {
        return values == null ? "" : String.join(" ", values);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private String errorCode(RuntimeException exception) {
        if (exception instanceof SimilarResearchAnalysisException analysis) {
            return analysis.code();
        }
        return "SIMILAR_RESEARCH_ANALYSIS_FAILED";
    }

    private String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? "相似研究分析失败" : value;
    }

    public static class SimilarResearchAnalysisException extends RuntimeException {
        private final String code;

        public SimilarResearchAnalysisException(
                String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
