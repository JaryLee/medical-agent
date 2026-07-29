package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.literature.LiteratureValidationModels;
import com.jarylee.medicalagent.literature.PubMedSearchModels;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimCitationValidationServiceTest {
    private static final List<String> SECTION_CODES = List.of(
            "TITLE", "ABSTRACT", "BACKGROUND", "RESEARCH_STATUS",
            "RESEARCH_GAP", "OBJECTIVES", "HYPOTHESIS", "STUDY_DESIGN",
            "PARTICIPANTS", "ELIGIBILITY", "OUTCOMES_VARIABLES",
            "DATA_COLLECTION", "STATISTICAL_ANALYSIS", "BIAS_CONTROL",
            "ETHICS_DATA_SECURITY", "SCHEDULE", "EXPECTED_RESULTS", "REFERENCES");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC);

    @Test
    void linksOnlyVerifiedPubmedEvidenceAndKeepsAbstractBoundary() {
        var repository = new MemoryClaimCitationValidationRepository();
        var service = new ClaimCitationValidationService(repository, json, clock);
        UUID hospitalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var protocol = protocol();
        var pubmed = pubmed();
        var validation = validation();

        var result = service.execute(
                hospitalId, projectId, taskId, protocol, pubmed, validation);
        var replayed = service.execute(
                hospitalId, projectId, taskId, protocol, pubmed, validation);

        assertThat(result.schemaVersion())
                .isEqualTo("claim-citation-validation-result/v1");
        assertThat(result.claims()).isNotEmpty();
        assertThat(result.claims())
                .extracting(ClaimCitationValidationModels.ResearchClaim::sectionCode)
                .contains("BACKGROUND", "RESEARCH_STATUS", "RESEARCH_GAP");
        assertThat(result.claims())
                .anySatisfy(claim -> {
                    assertThat(claim.supportStatus()).isEqualTo("ABSTRACT_ONLY");
                    assertThat(claim.citationLinks()).singleElement().satisfies(link -> {
                        assertThat(link.pmid()).isEqualTo("36331190");
                        assertThat(link.citationValidationStatus()).isEqualTo("VERIFIED");
                        assertThat(link.evidenceScope()).isEqualTo("ABSTRACT_ONLY");
                        assertThat(link.evidenceExcerpt()).contains("kidney disease");
                        assertThat(link.excerptSha256()).matches("[0-9a-f]{64}");
                        assertThat(link.manualConfirmationStatus())
                                .isEqualTo("PENDING_REVIEW");
                    });
                })
                .anySatisfy(claim -> {
                    assertThat(claim.sectionCode()).isEqualTo("RESEARCH_GAP");
                    assertThat(claim.supportStatus()).isEqualTo("NEEDS_EXPERT_REVIEW");
                    assertThat(claim.citationLinks()).isEmpty();
                    assertThat(claim.issuesToConfirm().toString()).contains("证据不足");
                });
        assertThat(json.valueToTree(result.claims()).toString())
                .doesNotContain(
                        "\"supportStatus\":\"SUPPORTED\"",
                        "\"evidenceScope\":\"FULL_TEXT\"");
        assertThat(replayed.validationTaskId()).isEqualTo(result.validationTaskId());
        assertThat(repository.all()).hasSize(1);
        assertThat(repository.claims(result.validationTaskId()))
                .hasSize(result.claimCount());
    }

    private ResearchProtocolModels.ProtocolDraft protocol() {
        List<ResearchProtocolModels.ProtocolSection> sections =
                IntStream.range(0, SECTION_CODES.size())
                        .mapToObj(index -> {
                            String code = SECTION_CODES.get(index);
                            List<String> sources = switch (code) {
                                case "BACKGROUND", "RESEARCH_STATUS" ->
                                        List.of("PMID:36331190", "PMID:99999999");
                                default -> List.of();
                            };
                            String content = switch (code) {
                                case "BACKGROUND" ->
                                        "当前证据只来自已记录的公开数据库检索。仍需全文审阅。";
                                case "RESEARCH_STATUS" ->
                                        "当前检索发现一项摘要级肾脏结局研究。";
                                case "RESEARCH_GAP" ->
                                        "当前证据未充分覆盖本院门诊人群。";
                                default -> code + " 章节草案内容。";
                            };
                            return new ResearchProtocolModels.ProtocolSection(
                                    UUID.randomUUID(), code, code, index + 1,
                                    "STATISTICAL_ANALYSIS".equals(code) ? 2 : 1,
                                    content, "MARKDOWN", "AGENT_DETERMINISTIC",
                                    sources.isEmpty()
                                            ? "NEEDS_EXPERT_REVIEW" : "ABSTRACT_ONLY",
                                    sources, List.of());
                        })
                        .toList();
        return new ResearchProtocolModels.ProtocolDraft(
                ResearchProtocolGenerationService.RESULT_SCHEMA_VERSION,
                UUID.randomUUID(), clock.instant(), StudyType.COHORT,
                "队列研究方案", sections, List.of(), "0".repeat(64),
                ResearchProtocolGenerationService.GENERATOR_VERSION, List.of());
    }

    private PubMedSearchModels.SearchResult pubmed() {
        var article = new PubMedSearchModels.Article(
                "36331190", "10.1056/NEJMoa2204233",
                "Empagliflozin in Patients with Chronic Kidney Disease",
                List.of("The EMPA-KIDNEY Collaborative Group"),
                "New England Journal of Medicine", "2023-01-12",
                "Empagliflozin was evaluated in participants with chronic kidney disease.",
                "ABSTRACT_ONLY", true, "PUBMED_EUTILS");
        return new PubMedSearchModels.SearchResult(
                "pubmed-search-result/v1", UUID.randomUUID(), "PUBMED",
                "kidney", "pubmed-query/v1", clock.instant(), 1, 1,
                List.of(article), "1".repeat(64), "application/json",
                "ncbi-eutils/v1", 3, List.of());
    }

    private LiteratureValidationModels.ValidationResult validation() {
        var citation = new LiteratureValidationModels.CitationValidation(
                "36331190", "10.1056/NEJMoa2204233", "VERIFIED",
                "CROSSREF", List.of(), null, "核心元数据一致");
        return new LiteratureValidationModels.ValidationResult(
                "literature-validation-result/v1", UUID.randomUUID(),
                clock.instant(), 1, 1, 0, 0, 0, 0,
                List.of(citation), List.of(), "2".repeat(64),
                "application/json", "crossref-rest/v1", 1, 0, List.of());
    }
}
