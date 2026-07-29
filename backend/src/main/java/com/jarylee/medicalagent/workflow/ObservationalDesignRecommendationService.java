package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisModels;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ObservationalDesignRecommendationService {
    public static final String RESULT_SCHEMA_VERSION =
            "observational-design-recommendation-result/v1";
    public static final String ALGORITHM_VERSION = "observational-design-rules/v1";
    public static final String PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    public static final String CONFIRMED = "CONFIRMED";

    private final ObservationalStudyRuleService ruleService;
    private final ObservationalDesignRecommendationRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public ObservationalDesignRecommendationService(
            ObservationalStudyRuleService ruleService,
            ObservationalDesignRecommendationRepository repository,
            ObjectMapper json,
            Clock clock) {
        this.ruleService = ruleService;
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    public ObservationalDesignRecommendationModels.Recommendation execute(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            AnalysisResult analysis,
            PecoDefinition peco,
            Map<String, String> clarificationAnswers,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        requireInputs(hospitalId, projectId, agentTaskId, analysis, peco, similarResearch);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("analysis", analysis);
        snapshot.put("peco", peco);
        snapshot.put("clarificationAnswers",
                clarificationAnswers == null ? Map.of() : clarificationAnswers);
        snapshot.put("similarResearchAnalysis", similarResearch);
        String inputHash = sha256(writeBytes(snapshot));
        UUID recommendationId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        repository.create(data(
                recommendationId, hospitalId, projectId, agentTaskId, "RUNNING",
                startedAt, null, inputHash, null, null));
        try {
            Map<String, String> answers =
                    clarificationAnswers == null ? Map.of() : clarificationAnswers;
            List<ObservationalDesignRecommendationModels.DesignAlternative> alternatives =
                    alternatives(analysis, peco, answers, similarResearch);
            ObservationalStudyRuleService.Assessment selected =
                    ruleService.assess(peco.studyType(), analysis, answers);
            List<String> unresolved = unresolvedItems(selected, similarResearch);
            boolean ready = selected.readyForDraft()
                    && peco.outcome() != null && !peco.outcome().isBlank();
            Instant recommendedAt = clock.instant();
            var result = new ObservationalDesignRecommendationModels.Recommendation(
                    RESULT_SCHEMA_VERSION,
                    recommendationId,
                    recommendedAt,
                    peco.studyType(),
                    peco.outcome(),
                    alternatives,
                    ready,
                    unresolved,
                    List.of(
                            "确认观察性研究类型",
                            "确认或修订主要终点",
                            "授权进入正式研究方案生成"),
                    PENDING_CONFIRMATION,
                    null,
                    null,
                    false,
                    null,
                    null,
                    inputHash,
                    ALGORITHM_VERSION,
                    List.of(
                            "该结果是基于已确认 PECO 与版本化规则生成的设计建议，不替代流行病学、统计学和临床专家评审。",
                            "相似研究分析受已检索数据库、检索日期、检索式和摘要级证据范围限制。",
                            "在人工确认研究类型、主要终点和授权前，不生成正式研究方案。"));
            String resultJson = write(result);
            repository.complete(
                    data(recommendationId, hospitalId, projectId, agentTaskId,
                            "COMPLETED", startedAt, recommendedAt, inputHash,
                            result, resultJson),
                    alternatives);
            return result;
        } catch (RuntimeException exception) {
            repository.fail(hospitalId, recommendationId,
                    "OBSERVATIONAL_DESIGN_RECOMMENDATION_FAILED",
                    safeMessage(exception), clock.instant());
            throw exception;
        }
    }

    public ObservationalDesignRecommendationModels.Recommendation confirm(
            ObservationalDesignRecommendationModels.Recommendation generated,
            ObservationalDesignRecommendationModels.Confirmation confirmation,
            UUID confirmedBy,
            Instant confirmedAt) {
        if (generated == null || !RESULT_SCHEMA_VERSION.equals(generated.schemaVersion())
                || !PENDING_CONFIRMATION.equals(generated.confirmationStatus())) {
            throw new IllegalStateException("观察性研究设计推荐当前不可确认");
        }
        if (!generated.readyForProtocolDraft()) {
            throw new IllegalStateException("设计所需信息尚不完整，不能授权进入正式研究方案生成");
        }
        if (confirmation == null || confirmation.studyType() == null) {
            throw new IllegalArgumentException("必须确认观察性研究类型");
        }
        boolean supported = generated.alternatives().stream()
                .anyMatch(value -> value.studyType() == confirmation.studyType());
        if (!supported) {
            throw new IllegalArgumentException("确认的研究类型不在本次设计备选项中");
        }
        String primaryOutcome = normalizeOutcome(confirmation.primaryOutcome());
        if (!confirmation.authorizeProtocolGeneration()) {
            throw new IllegalArgumentException("必须明确授权后才能进入正式研究方案生成");
        }
        if (confirmedBy == null || confirmedAt == null) {
            throw new IllegalArgumentException("缺少确认人或确认时间");
        }
        return new ObservationalDesignRecommendationModels.Recommendation(
                generated.schemaVersion(),
                generated.recommendationTaskId(),
                generated.recommendedAt(),
                generated.recommendedStudyType(),
                generated.primaryOutcomeCandidate(),
                generated.alternatives(),
                generated.readyForProtocolDraft(),
                generated.unresolvedItems(),
                generated.requiredConfirmations(),
                CONFIRMED,
                confirmation.studyType(),
                primaryOutcome,
                true,
                confirmedBy,
                confirmedAt,
                generated.inputSha256(),
                generated.algorithmVersion(),
                generated.limitations());
    }

    private List<ObservationalDesignRecommendationModels.DesignAlternative> alternatives(
            AnalysisResult analysis,
            PecoDefinition peco,
            Map<String, String> answers,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        List<ScoredAlternative> scored = new ArrayList<>();
        for (StudyType studyType : StudyType.values()) {
            ObservationalStudyRuleService.Assessment assessment =
                    ruleService.assess(studyType, analysis, answers);
            int score = (studyType == peco.studyType() ? 50 : 0)
                    + Math.max(0, 40 - assessment.missingFields().size() * 8)
                    + evidenceAlignment(studyType, similarResearch);
            scored.add(new ScoredAlternative(studyType, Math.min(100, score), assessment));
        }
        scored.sort(Comparator.comparingInt(ScoredAlternative::score).reversed()
                .thenComparing(value -> value.studyType() == peco.studyType() ? 0 : 1)
                .thenComparing(value -> value.studyType().name()));
        List<String> considerations = evidenceConsiderations(similarResearch);
        List<ObservationalDesignRecommendationModels.DesignAlternative> result =
                new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            ScoredAlternative value = scored.get(index);
            var assessment = value.assessment();
            result.add(new ObservationalDesignRecommendationModels.DesignAlternative(
                    index + 1,
                    value.studyType(),
                    value.score(),
                    assessment.readyForDraft() ? "READY" : "NEEDS_CLARIFICATION",
                    rationale(value.studyType(), assessment, peco.studyType()),
                    assessment.requiredFields(),
                    assessment.missingFields(),
                    biasRisks(value.studyType()),
                    considerations));
        }
        return List.copyOf(result);
    }

    private int evidenceAlignment(
            StudyType studyType,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        List<String> markers = switch (studyType) {
            case COHORT -> List.of("cohort", "longitudinal");
            case CROSS_SECTIONAL ->
                    List.of("cross-sectional", "cross sectional", "prevalence");
            case CASE_CONTROL -> List.of("case-control", "case control");
        };
        boolean matched = similarResearch.similarResearch().stream()
                .map(value -> value.title() == null
                        ? "" : value.title().toLowerCase(Locale.ROOT))
                .anyMatch(title -> markers.stream().anyMatch(title::contains));
        return matched ? 10 : 0;
    }

    private String rationale(
            StudyType studyType,
            ObservationalStudyRuleService.Assessment assessment,
            StudyType selectedType) {
        String selected = studyType == selectedType
                ? "与医生已确认的研究方向一致；" : "作为替代观察性设计进行比较；";
        String feasibility = assessment.readyForDraft()
                ? "当前必填信息完整。" : "仍缺少：" + String.join("、", assessment.missingFields()) + "。";
        return selected + assessment.explanation() + feasibility;
    }

    private List<String> evidenceConsiderations(
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        similarResearch.potentialResearchGaps().stream()
                .limit(3)
                .map(SimilarResearchAnalysisModels.ResearchGap::statement)
                .filter(value -> value != null && !value.isBlank())
                .forEach(values::add);
        if (similarResearch.highSimilarityCount() > 0) {
            values.add("发现高度相似研究，正式方案前必须由专家复核研究差异与新增价值。");
        }
        if (values.isEmpty()) {
            values.add("当前检索范围未形成可直接采用的研究空白结论，需结合完整文献评审确认。");
        }
        return List.copyOf(values);
    }

    private List<String> unresolvedItems(
            ObservationalStudyRuleService.Assessment selected,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        LinkedHashSet<String> values = new LinkedHashSet<>(selected.missingFields());
        if (similarResearch.highSimilarityCount() > 0) {
            values.add("复核高度相似研究与拟研究之间的实质差异");
        }
        return List.copyOf(values);
    }

    private List<String> biasRisks(StudyType studyType) {
        return switch (studyType) {
            case COHORT -> List.of("残余混杂", "时间零点或不死时间偏倚", "失访偏倚");
            case CROSS_SECTIONAL -> List.of("时序不明与因果推断限制", "选择偏倚", "患病率-幸存者偏倚");
            case CASE_CONTROL -> List.of("病例与对照选择偏倚", "回忆或信息偏倚", "对照来源与匹配不当");
        };
    }

    private ObservationalDesignRecommendationRepository.RecommendationData data(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            String status,
            Instant startedAt,
            Instant completedAt,
            String inputHash,
            ObservationalDesignRecommendationModels.Recommendation result,
            String resultJson) {
        return new ObservationalDesignRecommendationRepository.RecommendationData(
                id, hospitalId, projectId, agentTaskId, status, startedAt, completedAt,
                result == null ? null : result.recommendedStudyType().name(),
                result == null ? null : result.primaryOutcomeCandidate(),
                result == null ? null : result.readyForProtocolDraft(),
                result == null ? null : result.alternatives().size(),
                result == null ? null : write(result.unresolvedItems()),
                result == null ? null : write(result.requiredConfirmations()),
                inputHash, ALGORITHM_VERSION, resultJson, null, null);
    }

    private void requireInputs(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            AnalysisResult analysis,
            PecoDefinition peco,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        if (hospitalId == null || projectId == null || agentTaskId == null
                || analysis == null || analysis.profile() == null
                || peco == null || peco.studyType() == null
                || peco.outcome() == null || peco.outcome().isBlank()
                || similarResearch == null) {
            throw new IllegalArgumentException("观察性研究设计推荐输入不完整");
        }
    }

    private String normalizeOutcome(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("主要终点不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("主要终点不能超过 1000 个字符");
        }
        if (normalized.chars().anyMatch(character ->
                Character.isISOControl(character)
                        && character != '\n' && character != '\r' && character != '\t')) {
            throw new IllegalArgumentException("主要终点包含不支持的控制字符");
        }
        return normalized;
    }

    private byte[] writeBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException("观察性研究设计推荐输入序列化失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("观察性研究设计推荐结果序列化失败", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("观察性研究设计推荐输入哈希失败", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "未知错误";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record ScoredAlternative(
            StudyType studyType,
            int score,
            ObservationalStudyRuleService.Assessment assessment) {}
}
