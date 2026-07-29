package com.jarylee.medicalagent.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.ResearchOutputValidator;
import com.jarylee.medicalagent.agent.mock.MockResearchModel;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchModelEvaluationServiceTest {
    private final AnonymousResearchCaseRegistry cases =
            new AnonymousResearchCaseRegistry(new ObjectMapper());
    private final ResearchModelEvaluationService evaluation =
            new ResearchModelEvaluationService(new ResearchOutputValidator());

    @Test
    void evaluatesAllFiveSyntheticAnonymousCasesWithDeterministicMock() {
        var caseSet = cases.current();
        var run = evaluation.evaluate(
                caseSet, caseSet.cases(), new MockResearchModel(),
                new PromptTemplateRegistry().require("STEP_01_PARSE_IDEA"));

        assertThat(caseSet.dataClassification()).isEqualTo("SYNTHETIC_ANONYMOUS");
        assertThat(run.caseCount()).isEqualTo(5);
        assertThat(run.passedCount()).isEqualTo(5);
        assertThat(run.allPassed()).isTrue();
        assertThat(run.cases()).allSatisfy(result -> {
            assertThat(result.violations()).isEmpty();
            assertThat(result.directionStudyTypes()).hasSize(3);
            assertThat(result.clarificationQuestionCount()).isGreaterThanOrEqualTo(3);
        });
    }
}
