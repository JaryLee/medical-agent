package com.jarylee.medicalagent.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.ResearchOutputValidator;
import com.jarylee.medicalagent.agent.deepseek.DeepSeekResearchModel;
import com.jarylee.medicalagent.agent.mock.MockResearchModel;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_DEEPSEEK_EVALUATION", matches = "true")
class AnonymousModelComparisonLiveTest {
    @Test
    void comparesTwoSyntheticCasesWithoutPersistingModelContent() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var registry = new AnonymousResearchCaseRegistry(json);
        var evaluation = new ResearchModelEvaluationService(new ResearchOutputValidator());
        var caseSet = registry.current();
        var selected = caseSet.cases().subList(0, 2);
        var prompt = new PromptTemplateRegistry().require("STEP_01_PARSE_IDEA");
        var deepSeek = new DeepSeekResearchModel(
                json,
                RestClient.builder(),
                true,
                "https://api.deepseek.com",
                "deepseek-v4-flash",
                "",
                System.getenv("DEEPSEEK_TOKEN_FILE"),
                Duration.ofSeconds(10),
                Duration.ofSeconds(90));

        var mockRun = evaluation.evaluate(
                caseSet, selected, new MockResearchModel(), prompt);
        var deepSeekRun = evaluation.evaluate(
                caseSet, selected, deepSeek, prompt);

        assertThat(mockRun.allPassed()).isTrue();
        assertThat(deepSeekRun.allPassed()).isTrue();
        var differences = selected.stream().map(item -> {
            var mock = result(mockRun, item.id());
            var real = result(deepSeekRun, item.id());
            return new StructuralDifference(
                    item.id(),
                    mock.clarificationQuestionCount(),
                    real.clarificationQuestionCount(),
                    mock.missingInformationCount(),
                    real.missingInformationCount());
        }).toList();
        var report = new ComparisonReport(
                caseSet.schemaVersion(), caseSet.dataClassification(),
                mockRun, deepSeekRun, differences);
        System.out.println("ANONYMOUS_MODEL_COMPARISON=" + json.writeValueAsString(report));
    }

    private ResearchModelEvaluationService.CaseResult result(
            ResearchModelEvaluationService.EvaluationRun run, String caseId) {
        return run.cases().stream()
                .filter(item -> item.caseId().equals(caseId))
                .findFirst().orElseThrow();
    }

    record ComparisonReport(
            String datasetVersion,
            String dataClassification,
            ResearchModelEvaluationService.EvaluationRun mock,
            ResearchModelEvaluationService.EvaluationRun deepSeek,
            List<StructuralDifference> differences) {}

    record StructuralDifference(
            String caseId,
            int mockClarificationQuestions,
            int deepSeekClarificationQuestions,
            int mockMissingInformation,
            int deepSeekMissingInformation) {}
}
