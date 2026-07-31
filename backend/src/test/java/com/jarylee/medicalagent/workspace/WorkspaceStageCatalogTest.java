package com.jarylee.medicalagent.workspace;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceStageCatalogTest {
    @Test
    void mapsEveryWorkflowStepToOneStableBusinessStage() {
        assertThat(WorkspaceStageCatalog.STAGES)
                .extracting(WorkspaceStageCatalog.StageDefinition::code)
                .containsExactly(
                        "RESEARCH_IDEA",
                        "RESEARCH_DIRECTION",
                        "EVIDENCE",
                        "STUDY_DESIGN",
                        "PROTOCOL",
                        "STATISTICS",
                        "QUALITY",
                        "INTERNAL_REVIEW",
                        "DRAFT_EXPORT");
        assertThat(WorkspaceStageCatalog.STAGES)
                .extracting(WorkspaceStageCatalog.StageDefinition::label)
                .doesNotHaveDuplicates();

        String[] steps = {
                "STEP_01_PARSE_IDEA",
                "STEP_02_IDENTIFY_MISSING_INFORMATION",
                "STEP_03_ASK_CLARIFICATION",
                "STEP_04_GENERATE_RESEARCH_DIRECTIONS",
                "STEP_05_CONFIRM_DIRECTION",
                "STEP_06_BUILD_RESEARCH_QUESTION",
                "STEP_07_BUILD_SEARCH_STRATEGY",
                "STEP_08_SEARCH_PUBMED",
                "STEP_09_SEARCH_CLINICAL_TRIALS",
                "STEP_10_VALIDATE_LITERATURE",
                "STEP_11_ANALYZE_SIMILAR_RESEARCH",
                "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN",
                "STEP_13_GENERATE_PROTOCOL_SECTIONS",
                "STEP_14_GENERATE_STATISTICAL_DRAFT",
                "STEP_15_VALIDATE_CLAIMS_AND_CITATIONS",
                "STEP_16_CHECK_STROBE_COMPLETENESS",
                "STEP_17_WAIT_EXPERT_REVIEW",
                "STEP_18_EXPORT_DOCUMENT"
        };
        Set<Integer> mappedIndexes = new java.util.LinkedHashSet<>();
        for (String code : steps) {
            int index = WorkspaceStageCatalog.indexForStep(code);
            assertThat(index)
                    .as("%s must map to a declared stage", code)
                    .isBetween(0, WorkspaceStageCatalog.STAGES.size() - 1);
            mappedIndexes.add(index);
        }
        assertThat(mappedIndexes)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(
                                        0, WorkspaceStageCatalog.STAGES.size())
                                .boxed().toList());
    }

    @Test
    void rejectsUnknownOrMalformedInternalStepCodes() {
        assertThatThrownBy(() -> WorkspaceStageCatalog.indexForStep(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorkspaceStageCatalog.indexForStep(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                WorkspaceStageCatalog.indexForStep("STEP_19_UNKNOWN"))
                .isInstanceOf(IllegalStateException.class);
    }
}
