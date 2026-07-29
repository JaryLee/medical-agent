package com.jarylee.medicalagent.agent.evaluation;

import com.jarylee.medicalagent.agent.ResearchOutputValidator;
import com.jarylee.medicalagent.agent.model.ResearchModel;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class ResearchModelEvaluationService {
    private final ResearchOutputValidator validator;

    public ResearchModelEvaluationService(ResearchOutputValidator validator) {
        this.validator = validator;
    }

    public EvaluationRun evaluate(
            AnonymousResearchCaseRegistry.CaseSet caseSet,
            List<AnonymousResearchCaseRegistry.EvaluationCase> selectedCases,
            ResearchModel model,
            VersionedPrompt prompt) {
        List<CaseResult> results = selectedCases.stream()
                .map(item -> evaluateCase(item, model, prompt))
                .toList();
        long passed = results.stream().filter(CaseResult::passed).count();
        return new EvaluationRun(
                caseSet.schemaVersion(), caseSet.dataClassification(),
                model.provider(), model.modelName(), results.size(), Math.toIntExact(passed),
                passed == results.size(), results);
    }

    private CaseResult evaluateCase(
            AnonymousResearchCaseRegistry.EvaluationCase item,
            ResearchModel model,
            VersionedPrompt prompt) {
        List<String> violations = new ArrayList<>();
        try {
            AnalysisResult result = validator.validate(model.analyzeIdea(item.idea(), prompt));
            if (!"research-idea-profile/v1".equals(result.profile().schemaVersion())) {
                violations.add("PROFILE_SCHEMA_VERSION");
            }
            if (result.clarificationQuestions().size() < item.minimumClarificationQuestions()) {
                violations.add("TOO_FEW_CLARIFICATION_QUESTIONS");
            }
            var actualTypes = result.directions().stream()
                    .map(direction -> direction.recommendedStudyType())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!actualTypes.containsAll(item.expectedStudyTypes())) {
                violations.add("MISSING_EXPECTED_STUDY_TYPE");
            }
            String searchable = searchableText(result).toLowerCase(Locale.ROOT);
            List<String> missingKeywords = item.expectedKeywords().stream()
                    .filter(keyword -> !searchable.contains(keyword.toLowerCase(Locale.ROOT)))
                    .toList();
            if (!missingKeywords.isEmpty()) violations.add("MISSING_EXPECTED_KEYWORD");
            return new CaseResult(
                    item.id(), violations.isEmpty(), List.copyOf(violations),
                    result.clarificationQuestions().size(),
                    List.copyOf(actualTypes),
                    result.profile().missingInformation() == null
                            ? 0 : result.profile().missingInformation().size(),
                    missingKeywords);
        } catch (Exception exception) {
            return new CaseResult(
                    item.id(), false, List.of("MODEL_OR_SCHEMA_ERROR"),
                    0, List.of(), 0, item.expectedKeywords());
        }
    }

    private String searchableText(AnalysisResult result) {
        var profile = result.profile();
        StringBuilder text = new StringBuilder()
                .append(nullSafe(profile.specialty())).append(' ')
                .append(nullSafe(profile.clinicalProblem())).append(' ')
                .append(nullSafe(profile.population())).append(' ')
                .append(nullSafe(profile.exposure())).append(' ')
                .append(nullSafe(profile.comparator())).append(' ')
                .append(nullSafe(profile.outcome())).append(' ')
                .append(nullSafe(profile.researchPurpose()));
        result.directions().forEach(direction ->
                text.append(' ').append(nullSafe(direction.title()))
                        .append(' ').append(nullSafe(direction.researchPurpose())));
        return text.toString();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public record EvaluationRun(
            String datasetVersion,
            String dataClassification,
            String provider,
            String modelName,
            int caseCount,
            int passedCount,
            boolean allPassed,
            List<CaseResult> cases) {}

    public record CaseResult(
            String caseId,
            boolean passed,
            List<String> violations,
            int clarificationQuestionCount,
            List<StudyType> directionStudyTypes,
            int missingInformationCount,
            List<String> missingExpectedKeywords) {}
}
