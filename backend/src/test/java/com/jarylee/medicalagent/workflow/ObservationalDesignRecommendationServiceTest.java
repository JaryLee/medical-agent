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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationalDesignRecommendationServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void recommendsVersionedAlternativesAndRequiresExplicitHumanAuthorization() {
        var repository = new MemoryObservationalDesignRecommendationRepository();
        var service = new ObservationalDesignRecommendationService(
                new ObservationalStudyRuleService(
                        new ObservationalStudyRuleRegistry(json)),
                repository,
                json,
                clock);
        var generated = service.execute(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                analysis(), peco(), Map.of(), similarResearch());

        assertThat(generated.schemaVersion())
                .isEqualTo("observational-design-recommendation-result/v1");
        assertThat(generated.recommendedStudyType()).isEqualTo(StudyType.COHORT);
        assertThat(generated.alternatives()).hasSize(3);
        assertThat(generated.alternatives().getFirst().studyType())
                .isEqualTo(StudyType.COHORT);
        assertThat(generated.readyForProtocolDraft()).isTrue();
        assertThat(generated.confirmationStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(generated.inputSha256()).matches("[0-9a-f]{64}");
        assertThat(repository.all()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo("COMPLETED");
            assertThat(record.alternativeCount()).isEqualTo(3);
        });

        assertThatThrownBy(() -> service.confirm(
                generated,
                new ObservationalDesignRecommendationModels.Confirmation(
                        StudyType.COHORT, "12 个月 eGFR 绝对变化", false),
                UUID.randomUUID(),
                clock.instant()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("授权");

        UUID doctor = UUID.randomUUID();
        var confirmed = service.confirm(
                generated,
                new ObservationalDesignRecommendationModels.Confirmation(
                        StudyType.COHORT, " 12 个月 eGFR 绝对变化 ", true),
                doctor,
                clock.instant());
        assertThat(confirmed.confirmationStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedPrimaryOutcome())
                .isEqualTo("12 个月 eGFR 绝对变化");
        assertThat(confirmed.protocolGenerationAuthorized()).isTrue();
        assertThat(confirmed.confirmedBy()).isEqualTo(doctor);
    }

    private AnalysisResult analysis() {
        return new AnalysisResult(
                "analysis/v1",
                new ResearchIdeaProfile(
                        "profile/v1", "内分泌科", "糖尿病肾病",
                        "2 型糖尿病成年患者", "SGLT2 抑制剂",
                        "同类药物对照", "12 个月 eGFR 绝对变化",
                        "12 个月", "门诊电子病历", "评估关联", List.of()),
                List.of(),
                List.of(),
                "仅用于科研设计辅助");
    }

    private PecoDefinition peco() {
        return new PecoDefinition(
                "peco/v1", "2 型糖尿病成年患者", "SGLT2 抑制剂",
                "同类药物对照", "12 个月 eGFR 绝对变化",
                "SGLT2 抑制剂是否与 12 个月 eGFR 变化相关？",
                StudyType.COHORT,
                List.of());
    }

    private SimilarResearchAnalysisModels.AnalysisResult similarResearch() {
        return new SimilarResearchAnalysisModels.AnalysisResult(
                "similar-research-analysis-result/v1",
                UUID.randomUUID(),
                clock.instant(),
                peco().researchQuestion(),
                List.of("PUBMED", "CLINICAL_TRIALS_GOV"),
                0, 0, 0, 0, 0,
                List.of(),
                List.of(new SimilarResearchAnalysisModels.ResearchGap(
                        "POPULATION_EVIDENCE_GAP",
                        "当前证据未充分覆盖本院门诊人群。",
                        "检索结果的人群匹配不足",
                        List.of())),
                "未发现高度相似研究。",
                "0".repeat(64),
                "deterministic-peco-overlap/v1",
                List.of());
    }
}
