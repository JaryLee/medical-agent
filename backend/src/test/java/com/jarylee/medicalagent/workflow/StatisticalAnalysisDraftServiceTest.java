package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticalAnalysisDraftServiceTest {
    private static final List<String> SECTION_CODES = List.of(
            "TITLE", "BACKGROUND", "OBJECTIVES", "RESEARCH_QUESTION",
            "STUDY_DESIGN", "SETTING", "PARTICIPANTS", "EXPOSURE",
            "COMPARATOR", "OUTCOMES", "COVARIATES", "BIAS_CONTROL",
            "STATISTICAL_ANALYSIS", "SAMPLE_SIZE", "DATA_MANAGEMENT",
            "ETHICS", "DISSEMINATION", "REFERENCES");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsVersionTwoDraftWithoutGuessingSampleSizeParameters() {
        var repository = new MemoryStatisticalAnalysisDraftRepository();
        var service = new StatisticalAnalysisDraftService(repository, json, clock);
        UUID hospitalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var protocol = protocol();
        var design = design();

        var result = service.execute(
                hospitalId, projectId, taskId, protocol, design);
        var replayed = service.execute(
                hospitalId, projectId, taskId, protocol, design);
        var updatedProtocol = service.applyToProtocol(protocol, result);

        assertThat(result.schemaVersion()).isEqualTo("statistical-analysis-draft/v1");
        assertThat(result.studyType()).isEqualTo(StudyType.COHORT);
        assertThat(result.primaryAnalysisCandidates())
                .anyMatch(value -> value.contains("生存分析"));
        assertThat(result.sampleSizeParameters()).hasSize(8)
                .allSatisfy(parameter -> {
                    assertThat(parameter.valueStatus())
                            .isEqualTo("MISSING_NEEDS_INPUT");
                    assertThat(parameter.value()).isNull();
                });
        assertThat(result.statisticalSectionVersion().versionNo()).isEqualTo(2);
        assertThat(result.statisticalSectionVersion().sectionId())
                .isEqualTo(protocol.sections().get(12).sectionId());
        assertThat(result.statisticalSectionVersion().content())
                .contains("MISSING_NEEDS_INPUT")
                .doesNotContain("样本量为");
        assertThat(replayed.draftId()).isEqualTo(result.draftId());
        assertThat(repository.all()).hasSize(1);
        assertThat(repository.parameters(result.draftId())).hasSize(8);
        assertThat(updatedProtocol.sections().get(12).versionNo()).isEqualTo(2);
        assertThat(updatedProtocol.sections()).hasSize(18);
    }

    private ResearchProtocolModels.ProtocolDraft protocol() {
        List<ResearchProtocolModels.ProtocolSection> sections =
                IntStream.range(0, SECTION_CODES.size())
                        .mapToObj(index -> new ResearchProtocolModels.ProtocolSection(
                                UUID.randomUUID(), SECTION_CODES.get(index),
                                SECTION_CODES.get(index), index + 1, 1,
                                "STEP13 初始章节", "MARKDOWN",
                                "AGENT_DETERMINISTIC", "NEEDS_EXPERT_REVIEW",
                                List.of("STEP13"), List.of()))
                        .toList();
        return new ResearchProtocolModels.ProtocolDraft(
                ResearchProtocolGenerationService.RESULT_SCHEMA_VERSION,
                UUID.randomUUID(), clock.instant(), StudyType.COHORT,
                "队列研究方案", sections, List.of(), "0".repeat(64),
                ResearchProtocolGenerationService.GENERATOR_VERSION, List.of());
    }

    private ObservationalDesignRecommendationModels.Recommendation design() {
        return new ObservationalDesignRecommendationModels.Recommendation(
                ObservationalDesignRecommendationService.RESULT_SCHEMA_VERSION,
                UUID.randomUUID(), clock.instant(), StudyType.COHORT,
                "12个月 eGFR 绝对变化", List.of(), true, List.of(), List.of(),
                ObservationalDesignRecommendationService.CONFIRMED,
                StudyType.COHORT, "12个月 eGFR 绝对变化", true,
                UUID.randomUUID(), clock.instant(), "1".repeat(64),
                "observational-design-rules/v1", List.of());
    }
}
