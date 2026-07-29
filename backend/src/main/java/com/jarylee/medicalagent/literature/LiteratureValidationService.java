package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.file.ObjectStorage;
import com.jarylee.medicalagent.literature.CrossrefRestMetadataGateway.CrossrefMetadataException;
import com.jarylee.medicalagent.literature.LiteratureValidationModels.CitationValidation;
import com.jarylee.medicalagent.literature.LiteratureValidationModels.EvidenceLink;
import com.jarylee.medicalagent.literature.LiteratureValidationModels.FieldCheck;
import com.jarylee.medicalagent.literature.LiteratureValidationModels.RawEvidence;
import com.jarylee.medicalagent.literature.LiteratureValidationModels.ValidationResult;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class LiteratureValidationService {
    public static final String RESULT_SCHEMA_VERSION = "literature-validation-result/v1";
    private static final String RAW_CONTENT_TYPE = "application/json";
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final CrossrefMetadataGateway gateway;
    private final LiteratureValidationRepository repository;
    private final ObjectStorage storage;
    private final ObjectMapper json;
    private final Clock clock;

    public LiteratureValidationService(
            CrossrefMetadataGateway gateway,
            LiteratureValidationRepository repository,
            ObjectStorage storage,
            ObjectMapper json,
            Clock clock) {
        this.gateway = gateway;
        this.repository = repository;
        this.storage = storage;
        this.json = json;
        this.clock = clock;
    }

    public ValidationResult execute(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            PubMedSearchModels.SearchResult pubmed,
            ClinicalTrialsSearchModels.SearchResult clinicalTrials) {
        if (pubmed == null || pubmed.records() == null) {
            throw new IllegalArgumentException("缺少 PubMed 检索结果");
        }
        UUID validationId = UUID.randomUUID();
        Instant started = clock.instant();
        repository.create(new LiteratureValidationRepository.ValidationData(
                validationId, hospitalId, projectId, agentTaskId, "RUNNING",
                started, null, null, null, null, null, null, null,
                null, null, null, null));

        String objectKey = null;
        try {
            List<CitationValidation> citations = new ArrayList<>();
            List<RawEvidence> rawEvidence = new ArrayList<>();
            int requestCount = 0;
            int cacheHits = 0;
            String toolVersion = "crossref-rest/v1";
            for (var article : pubmed.records()) {
                if (article.doi() == null || article.doi().isBlank()) {
                    citations.add(new CitationValidation(
                            article.pmid(), null, "DOI_NOT_AVAILABLE", "PUBMED_ONLY",
                            List.of(), null, "PubMed 记录未提供 DOI，无法执行 Crossref 校验"));
                    continue;
                }
                var result = gateway.lookup(article.doi());
                requestCount += result.externalRequestCount();
                if (result.cacheHit()) cacheHits++;
                toolVersion = result.toolVersion();
                rawEvidence.add(new RawEvidence(
                        article.pmid(), article.doi(), result.found(),
                        result.rawContentType(), result.rawResponse()));
                citations.add(validate(article, result));
            }

            List<EvidenceLink> evidenceLinks = buildEvidenceLinks(pubmed, clinicalTrials);
            byte[] raw = json.writeValueAsBytes(rawEvidence);
            String hash = sha256(raw);
            objectKey = objectKey(hospitalId, projectId, validationId);
            storage.put(objectKey, raw, RAW_CONTENT_TYPE);
            Instant completed = clock.instant();
            var completedData = new LiteratureValidationRepository.ValidationData(
                    validationId, hospitalId, projectId, agentTaskId, "COMPLETED",
                    started, completed, citations.size(), evidenceLinks.size(),
                    objectKey, hash, RAW_CONTENT_TYPE, toolVersion, requestCount,
                    cacheHits, null, null);
            repository.complete(completedData, citations, evidenceLinks);

            return result(validationId, completed, citations, evidenceLinks, hash,
                    toolVersion, requestCount, cacheHits);
        } catch (RuntimeException exception) {
            if (objectKey != null) {
                try {
                    storage.delete(objectKey);
                } catch (RuntimeException ignored) {
                    // Preserve the primary failure; orphan reconciliation is operational.
                }
            }
            repository.fail(hospitalId, validationId, errorCode(exception),
                    safeMessage(exception), clock.instant());
            if (exception instanceof CrossrefMetadataException) throw exception;
            throw new LiteratureValidationException(
                    "LITERATURE_VALIDATION_FAILED", "文献验证执行失败", exception);
        } catch (Exception exception) {
            repository.fail(hospitalId, validationId, "LITERATURE_VALIDATION_FAILED",
                    "文献验证执行失败", clock.instant());
            throw new LiteratureValidationException(
                    "LITERATURE_VALIDATION_FAILED", "文献验证执行失败", exception);
        }
    }

    private CitationValidation validate(
            PubMedSearchModels.Article article,
            CrossrefMetadataModels.GatewayResult result) {
        if (!result.found()) {
            return new CitationValidation(
                    article.pmid(), normalizeDoi(article.doi()), "CROSSREF_NOT_FOUND",
                    "CROSSREF", List.of(), null, "Crossref 未找到该 DOI");
        }
        var work = result.work();
        List<FieldCheck> checks = List.of(
                check("doi", normalizeDoi(article.doi()), normalizeDoi(work.doi()), true),
                check("title", article.title(), work.title(), true),
                checkAuthors(article.authors(), work.authors()),
                check("journal", article.journal(), work.journal(), false),
                check("publicationYear", year(article.publicationDate()),
                        year(work.publicationDate()), false));
        boolean requiredMismatch = checks.stream()
                .filter(check -> "doi".equals(check.field()) || "title".equals(check.field()))
                .anyMatch(check -> "MISMATCH".equals(check.status()));
        boolean anyMismatch = checks.stream()
                .anyMatch(check -> "MISMATCH".equals(check.status()));
        String status = requiredMismatch
                ? "MISMATCH"
                : anyMismatch ? "VERIFIED_WITH_METADATA_DIFFERENCES" : "VERIFIED";
        String message = switch (status) {
            case "VERIFIED" -> "Crossref DOI 与 PubMed 核心元数据一致";
            case "VERIFIED_WITH_METADATA_DIFFERENCES" -> "DOI 与标题一致，部分辅助元数据不同";
            default -> "Crossref 与 PubMed 核心元数据不一致，需要人工复核";
        };
        return new CitationValidation(
                article.pmid(), normalizeDoi(article.doi()), status,
                "CROSSREF", checks, work, message);
    }

    private FieldCheck check(String field, String expected, String actual, boolean exact) {
        if (blank(expected) || blank(actual)) {
            return new FieldCheck(field, "NOT_AVAILABLE", expected, actual);
        }
        String left = normalize(expected);
        String right = normalize(actual);
        boolean matches = exact ? left.equals(right)
                : left.equals(right) || left.contains(right) || right.contains(left);
        return new FieldCheck(field, matches ? "MATCH" : "MISMATCH", expected, actual);
    }

    private FieldCheck checkAuthors(List<String> pubmed, List<String> crossref) {
        String expected = join(pubmed);
        String actual = join(crossref);
        if (blank(expected) || blank(actual)) {
            return new FieldCheck("authors", "NOT_AVAILABLE", expected, actual);
        }
        Set<String> left = normalizedAuthors(pubmed);
        Set<String> right = normalizedAuthors(crossref);
        boolean matches = left.stream().anyMatch(author ->
                right.stream().anyMatch(other ->
                        author.equals(other) || author.contains(other) || other.contains(author)));
        return new FieldCheck(
                "authors", matches ? "MATCH" : "MISMATCH", expected, actual);
    }

    private List<EvidenceLink> buildEvidenceLinks(
            PubMedSearchModels.SearchResult pubmed,
            ClinicalTrialsSearchModels.SearchResult clinicalTrials) {
        if (clinicalTrials == null || clinicalTrials.records() == null) return List.of();
        Set<String> available = new HashSet<>();
        pubmed.records().forEach(article -> available.add(article.pmid()));
        Set<String> seen = new HashSet<>();
        List<EvidenceLink> result = new ArrayList<>();
        for (var trial : clinicalTrials.records()) {
            if (trial.linkedPmids() == null) continue;
            for (String pmid : trial.linkedPmids()) {
                String key = trial.nctId() + "\u0000" + pmid;
                if (pmid == null || pmid.isBlank() || !seen.add(key)) continue;
                result.add(new EvidenceLink(
                        trial.nctId(), pmid, "REGISTRY_REFERENCES_PUBLICATION",
                        available.contains(pmid) ? "RESOLVED" : "UNRESOLVED_PUBMED"));
            }
        }
        return List.copyOf(result);
    }

    private ValidationResult result(
            UUID id, Instant completed, List<CitationValidation> citations,
            List<EvidenceLink> links, String hash, String toolVersion,
            int requestCount, int cacheHits) {
        return new ValidationResult(
                RESULT_SCHEMA_VERSION, id, completed, citations.size(),
                count(citations, "VERIFIED"),
                count(citations, "VERIFIED_WITH_METADATA_DIFFERENCES"),
                count(citations, "MISMATCH"),
                count(citations, "CROSSREF_NOT_FOUND"),
                count(citations, "DOI_NOT_AVAILABLE"),
                List.copyOf(citations), links, hash, RAW_CONTENT_TYPE,
                toolVersion, requestCount, cacheHits,
                List.of(
                        "Crossref 元数据校验用于核对引文身份，不替代全文审阅或偏倚风险评价",
                        "无 DOI 或 Crossref 未收录的记录需要人工核查",
                        "ClinicalTrials.gov 注册记录属于注册来源证据，不等同于同行评议论文",
                        "注册研究关联仅依据注册记录公开 PMID，未解析全文中的全部研究编号"));
    }

    private int count(List<CitationValidation> values, String status) {
        return (int) values.stream().filter(value -> status.equals(value.status())).count();
    }

    private Set<String> normalizedAuthors(List<String> authors) {
        if (authors == null) return Set.of();
        Set<String> result = new HashSet<>();
        for (String author : authors) {
            String normalized = normalize(author);
            if (!normalized.isBlank()) {
                result.add(normalized);
                String[] parts = normalized.split(" ");
                if (parts.length > 0) result.add(parts[parts.length - 1]);
            }
        }
        return result;
    }

    private String join(List<String> values) {
        return values == null ? null : String.join("; ", values);
    }

    private String year(String value) {
        if (value == null) return null;
        var matcher = Pattern.compile("(\\d{4})").matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalizeDoi(String value) {
        if (value == null) return null;
        return value.strip().replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "")
                .toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return NON_ALNUM.matcher(
                TAG.matcher(value).replaceAll(" ").toLowerCase(Locale.ROOT))
                .replaceAll(" ").strip();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String objectKey(UUID hospitalId, UUID projectId, UUID validationId) {
        return hospitalId + "/" + projectId + "/literature-validation/"
                + validationId + "/crossref-raw-v1.json";
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("Crossref 原始响应哈希失败", exception);
        }
    }

    private String errorCode(RuntimeException exception) {
        if (exception instanceof CrossrefMetadataException crossref) return crossref.code();
        if (exception instanceof LiteratureValidationException validation) return validation.code();
        return "LITERATURE_VALIDATION_FAILED";
    }

    private String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? "文献验证失败" : value;
    }

    public static class LiteratureValidationException extends RuntimeException {
        private final String code;

        public LiteratureValidationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
