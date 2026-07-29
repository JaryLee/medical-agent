package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
import com.jarylee.medicalagent.agent.model.ResearchModels.ResearchIdeaProfile;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisModels;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchProtocolGenerationServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsEighteenVersionedSectionsWithoutInventingStatisticsOrCitations() {
        var repository = new MemoryResearchProtocolRepository();
        var service = new ResearchProtocolGenerationService(repository, json, clock);

        UUID hospitalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var analysis = analysis();
        var peco = peco();
        var design = confirmedDesign();
        var similar = similarResearch();
        var draft = service.execute(
                hospitalId, projectId, taskId,
                analysis, peco, design, similar);
        var replayed = service.execute(
                hospitalId, projectId, taskId,
                analysis, peco, design, similar);

        assertThat(draft.schemaVersion()).isEqualTo("research-protocol-draft/v1");
        assertThat(draft.studyType()).isEqualTo(StudyType.COHORT);
        assertThat(draft.sections()).hasSize(18);
        assertThat(draft.sections())
                .extracting(ResearchProtocolModels.ProtocolSection::sortOrder)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9,
                        10, 11, 12, 13, 14, 15, 16, 17, 18);
        assertThat(section(draft, "STATISTICAL_ANALYSIS").content())
                .contains("STEP14", "不会自动猜测样本量")
                .doesNotContain("样本量为");
        assertThat(section(draft, "REFERENCES").content())
                .contains("PMID:36331190", "DOI:10.1056/NEJMoa2204233")
                .doesNotContain("PMID:00000000");
        assertThat(draft.inputSha256()).matches("[0-9a-f]{64}");
        assertThat(replayed.protocolId()).isEqualTo(draft.protocolId());
        assertThat(repository.all()).singleElement().satisfies(protocol -> {
            assertThat(protocol.status()).isEqualTo("DRAFT");
            assertThat(protocol.generatorVersion())
                    .isEqualTo("deterministic-observational-protocol/v1");
        });
        assertThat(repository.sections(draft.protocolId()))
                .allSatisfy(section -> {
                    assertThat(section.versionNo()).isEqualTo(1);
                    assertThat(section.origin()).isEqualTo("AGENT_DETERMINISTIC");
                });
    }

    private ResearchProtocolModels.ProtocolSection section(
            ResearchProtocolModels.ProtocolDraft draft, String code) {
        return draft.sections().stream()
                .filter(value -> code.equals(value.sectionCode()))
                .findFirst().orElseThrow();
    }

    private AnalysisResult analysis() {
        return new AnalysisResult(
                "analysis/v1",
                new ResearchIdeaProfile(
                        "profile/v1", "内分泌科", "糖尿病肾病",
                        "2 型糖尿病成年患者", "SGLT2 抑制剂",
                        "同类药物对照", "12 个月 eGFR 绝对变化",
                        "12 个月", "门诊电子病历", "评估关联", List.of()),
                List.of(), List.of(), "仅用于科研设计辅助");
    }

    private PecoDefinition peco() {
        return new PecoDefinition(
                "peco/v1", "2 型糖尿病成年患者", "SGLT2 抑制剂",
                "同类药物对照", "12 个月 eGFR 绝对变化",
                "SGLT2 抑制剂是否与 12 个月 eGFR 变化相关？",
                StudyType.COHORT, List.of());
    }

    private ObservationalDesignRecommendationModels.Recommendation confirmedDesign() {
        return new ObservationalDesignRecommendationModels.Recommendation(
                "observational-design-recommendation-result/v1",
                UUID.randomUUID(), clock.instant(), StudyType.COHORT,
                "12 个月 eGFR 绝对变化",
                List.of(new ObservationalDesignRecommendationModels.DesignAlternative(
                        1, StudyType.COHORT, 100, "READY", "队列设计",
                        List.of(), List.of(),
                        List.of("残余混杂", "失访偏倚"), List.of())),
                true, List.of(), List.of(), "CONFIRMED", StudyType.COHORT,
                "12 个月 eGFR 绝对变化", true, UUID.randomUUID(),
                clock.instant(), "0".repeat(64), "observational-design-rules/v1",
                List.of());
    }

    private SimilarResearchAnalysisModels.AnalysisResult similarResearch() {
        var source = new SimilarResearchAnalysisModels.SimilarResearch(
                "PUBMED_ARTICLE", "36331190", "36331190",
                "10.1056/NEJMoa2204233", null,
                "Empagliflozin in Patients with Chronic Kidney Disease",
                "2023-01-12", 55, "MODERATE", "VERIFIED",
                "ABSTRACT_ONLY", List.of(), List.of(), List.of());
        return new SimilarResearchAnalysisModels.AnalysisResult(
                "similar-research-analysis-result/v1", UUID.randomUUID(),
                clock.instant(), peco().researchQuestion(),
                List.of("PUBMED", "CROSSREF"), 1, 0, 0, 1, 0,
                List.of(source),
                List.of(new SimilarResearchAnalysisModels.ResearchGap(
                        "POPULATION_EVIDENCE_GAP",
                        "当前证据未充分覆盖本院门诊人群。",
                        "人群维度未完全匹配", List.of("36331190"))),
                "未发现高度相似研究。",
                "1".repeat(64), "deterministic-peco-overlap/v1", List.of());
    }
}
