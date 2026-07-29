package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.literature.LiteratureValidationModels;
import com.jarylee.medicalagent.literature.PubMedSearchModels;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ClaimCitationValidationService {
    public static final String RESULT_SCHEMA_VERSION =
            "claim-citation-validation-result/v1";
    public static final String VALIDATOR_VERSION =
            "deterministic-claim-citation-linker/v1";

    private static final Set<String> EVIDENCE_SECTION_CODES = Set.of(
            "BACKGROUND", "RESEARCH_STATUS", "RESEARCH_GAP");
    private static final Set<String> LINKABLE_VALIDATION_STATUSES = Set.of(
            "VERIFIED", "VERIFIED_WITH_METADATA_DIFFERENCES");
    private static final Pattern CLAIM_BOUNDARY =
            Pattern.compile("(?<=[。！？；])\\s*|\\R+");

    private final ClaimCitationValidationRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public ClaimCitationValidationService(
            ClaimCitationValidationRepository repository,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    public ClaimCitationValidationModels.ValidationResult execute(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            ResearchProtocolModels.ProtocolDraft protocol,
            PubMedSearchModels.SearchResult pubmed,
            LiteratureValidationModels.ValidationResult validation) {
        requireInputs(hospitalId, projectId, agentTaskId, protocol, pubmed, validation);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("protocolDraft", protocol);
        snapshot.put("pubmedSearch", pubmed);
        snapshot.put("literatureValidation", validation);
        String inputHash = sha256(writeBytes(snapshot));
        var existing = repository.findByAgentTask(hospitalId, agentTaskId);
        if (existing.isPresent()) {
            if (!inputHash.equals(existing.get().inputSha256())) {
                throw new IllegalStateException(
                        "同一 Agent 任务已存在输入不一致的主张与引用验证结果");
            }
            return read(existing.get().resultJson());
        }

        Map<String, PubMedSearchModels.Article> articles = new HashMap<>();
        pubmed.records().stream()
                .filter(PubMedSearchModels.Article::verified)
                .forEach(article -> articles.put(article.pmid(), article));
        Map<String, LiteratureValidationModels.CitationValidation> citations =
                new HashMap<>();
        validation.citations().stream()
                .filter(value -> LINKABLE_VALIDATION_STATUSES.contains(value.status()))
                .forEach(value -> citations.put(value.pmid(), value));

        List<ClaimCitationValidationModels.ResearchClaim> claims =
                buildClaims(protocol, articles, citations);
        int linkCount = claims.stream()
                .mapToInt(value -> value.citationLinks().size()).sum();
        int abstractOnlyCount = (int) claims.stream()
                .filter(value -> "ABSTRACT_ONLY".equals(value.supportStatus()))
                .count();
        int expertReviewCount = claims.size() - abstractOnlyCount;
        UUID validationTaskId = UUID.randomUUID();
        Instant validatedAt = clock.instant();
        var result = new ClaimCitationValidationModels.ValidationResult(
                RESULT_SCHEMA_VERSION,
                validationTaskId,
                protocol.protocolId(),
                validatedAt,
                claims.size(),
                linkCount,
                abstractOnlyCount,
                expertReviewCount,
                claims,
                inputHash,
                VALIDATOR_VERSION,
                List.of(
                        "当前没有接入合法开放的 PMC 全文，因此不会输出 FULL_TEXT 或 SUPPORTED 结论。",
                        "ABSTRACT_ONLY 只表示找到了经过 STEP10 核验的摘要级候选依据，不表示摘要已充分支持主张。",
                        "无可追溯核验引用的主张标记为 NEEDS_EXPERT_REVIEW，并明确记录证据不足。",
                        "主张、依据片段和支持关系必须由医学或科研专家逐条确认；本步骤不替代全文审阅。"));
        repository.save(
                new ClaimCitationValidationRepository.ValidationTaskData(
                        validationTaskId,
                        hospitalId,
                        projectId,
                        agentTaskId,
                        protocol.protocolId(),
                        "WAITING_EXPERT_REVIEW",
                        result.claimCount(),
                        result.citationLinkCount(),
                        result.abstractOnlyClaimCount(),
                        result.needsExpertReviewClaimCount(),
                        inputHash,
                        VALIDATOR_VERSION,
                        write(result),
                        validatedAt),
                claims);
        return result;
    }

    private List<ClaimCitationValidationModels.ResearchClaim> buildClaims(
            ResearchProtocolModels.ProtocolDraft protocol,
            Map<String, PubMedSearchModels.Article> articles,
            Map<String, LiteratureValidationModels.CitationValidation> citations) {
        List<ClaimCitationValidationModels.ResearchClaim> result = new ArrayList<>();
        protocol.sections().stream()
                .filter(section -> EVIDENCE_SECTION_CODES.contains(section.sectionCode()))
                .sorted(Comparator.comparingInt(ResearchProtocolModels.ProtocolSection::sortOrder))
                .forEach(section -> {
                    List<String> statements = splitClaims(section.content());
                    for (int index = 0; index < statements.size(); index++) {
                        UUID claimId = UUID.randomUUID();
                        List<ClaimCitationValidationModels.CitationLink> links =
                                buildLinks(claimId, section.sourceIdentifiers(),
                                        articles, citations);
                        boolean hasAbstract = links.stream().anyMatch(
                                link -> "ABSTRACT_ONLY".equals(link.supportLevel()));
                        String supportStatus = hasAbstract
                                ? "ABSTRACT_ONLY" : "NEEDS_EXPERT_REVIEW";
                        List<String> issues = hasAbstract
                                ? List.of(
                                        "只定位到摘要级候选依据，尚未判定其是否充分支持本主张",
                                        "必须完成全文审阅并由专家确认支持关系")
                                : List.of(
                                        "没有可追溯到 STEP10 核验结果的引用，当前证据不足",
                                        "由专家补充合规来源或删除/改写该主张");
                        result.add(new ClaimCitationValidationModels.ResearchClaim(
                                claimId,
                                section.sectionId(),
                                section.sectionCode(),
                                index + 1,
                                claimType(section.sectionCode()),
                                statements.get(index),
                                supportStatus,
                                "PENDING_REVIEW",
                                links,
                                issues));
                    }
                });
        if (result.isEmpty()) {
            throw new IllegalStateException("研究方案没有可验证的事实性主张章节");
        }
        return List.copyOf(result);
    }

    private List<ClaimCitationValidationModels.CitationLink> buildLinks(
            UUID claimId,
            List<String> sourceIdentifiers,
            Map<String, PubMedSearchModels.Article> articles,
            Map<String, LiteratureValidationModels.CitationValidation> citations) {
        List<ClaimCitationValidationModels.CitationLink> links = new ArrayList<>();
        if (sourceIdentifiers == null) return List.of();
        for (String identifier : sourceIdentifiers) {
            String pmid = normalizePmid(identifier);
            if (pmid == null || links.stream().anyMatch(link -> pmid.equals(link.pmid()))) {
                continue;
            }
            var article = articles.get(pmid);
            var citation = citations.get(pmid);
            if (article == null || citation == null) continue;
            String abstractText = clean(article.abstractText());
            boolean hasAbstract = abstractText != null && !abstractText.isBlank();
            String excerpt = hasAbstract
                    ? truncate(abstractText, 700)
                    : truncate(article.title(), 700);
            String scope = hasAbstract ? "ABSTRACT_ONLY" : "TITLE_ONLY";
            String support = hasAbstract ? "ABSTRACT_ONLY" : "NEEDS_EXPERT_REVIEW";
            links.add(new ClaimCitationValidationModels.CitationLink(
                    UUID.randomUUID(),
                    claimId,
                    links.size() + 1,
                    "PUBMED",
                    pmid,
                    citation.doi(),
                    article.title(),
                    support,
                    scope,
                    excerpt,
                    hasAbstract ? "PUBMED_ABSTRACT" : "PUBMED_TITLE_ONLY",
                    sha256(excerpt.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    citation.status(),
                    "PENDING_REVIEW"));
        }
        return List.copyOf(links);
    }

    private List<String> splitClaims(String content) {
        if (content == null || content.isBlank()) return List.of();
        List<String> claims = new ArrayList<>();
        for (String value : CLAIM_BOUNDARY.split(content)) {
            String cleaned = value.strip()
                    .replaceFirst("^[-*]\\s*", "")
                    .replaceFirst("^#+\\s*", "");
            if (cleaned.length() >= 8) claims.add(cleaned);
        }
        return List.copyOf(claims);
    }

    private String claimType(String sectionCode) {
        return switch (sectionCode) {
            case "BACKGROUND" -> "BACKGROUND_STATEMENT";
            case "RESEARCH_STATUS" -> "EVIDENCE_SUMMARY";
            case "RESEARCH_GAP" -> "POTENTIAL_RESEARCH_GAP";
            default -> throw new IllegalArgumentException("不支持的主张章节: " + sectionCode);
        };
    }

    private String normalizePmid(String identifier) {
        if (identifier == null) return null;
        String value = identifier.strip();
        if (value.regionMatches(true, 0, "PMID:", 0, 5)) {
            value = value.substring(5).strip();
        }
        return value.matches("\\d{1,20}") ? value : null;
    }

    private String clean(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").strip();
    }

    private String truncate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private void requireInputs(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            ResearchProtocolModels.ProtocolDraft protocol,
            PubMedSearchModels.SearchResult pubmed,
            LiteratureValidationModels.ValidationResult validation) {
        if (hospitalId == null || projectId == null || agentTaskId == null
                || protocol == null || pubmed == null || validation == null) {
            throw new IllegalArgumentException("主张与引用验证输入不完整");
        }
        if (!ResearchProtocolGenerationService.RESULT_SCHEMA_VERSION.equals(
                protocol.schemaVersion())
                || protocol.sections() == null || protocol.sections().size() != 18) {
            throw new IllegalStateException("研究方案章节未完整生成");
        }
        var statisticalSection = protocol.sections().stream()
                .filter(section -> "STATISTICAL_ANALYSIS".equals(section.sectionCode()))
                .findFirst().orElseThrow();
        if (statisticalSection.versionNo() != 2) {
            throw new IllegalStateException("STEP14 统计分析章节尚未形成 v2");
        }
        if (!"PUBMED".equals(pubmed.database())
                || pubmed.records() == null || validation.citations() == null) {
            throw new IllegalStateException("STEP10 核验文献输入不完整");
        }
    }

    private byte[] writeBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException("主张与引用验证输入序列化失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("主张与引用验证结果序列化失败", exception);
        }
    }

    private ClaimCitationValidationModels.ValidationResult read(String value) {
        try {
            return json.readValue(
                    value, ClaimCitationValidationModels.ValidationResult.class);
        } catch (Exception exception) {
            throw new IllegalStateException("已持久化主张与引用验证结果损坏", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("主张与引用验证哈希失败", exception);
        }
    }
}
