package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ObservationalStudyRuleService {
    private final ObservationalStudyRuleRegistry registry;

    public ObservationalStudyRuleService(ObservationalStudyRuleRegistry registry) {
        this.registry = registry;
    }

    public Assessment assess(StudyType studyType, AnalysisResult analysis,
                             Map<String, String> clarificationAnswers) {
        var rules = registry.current();
        var studyRule = rules.studyTypes().get(studyType);
        List<String> required = studyRule.requiredFields();
        List<String> missing = new ArrayList<>();
        for (String field : required) {
            if (!resolved(field, analysis, clarificationAnswers, rules)) missing.add(field);
        }
        return new Assessment(studyType, required, List.copyOf(missing),
                missing.isEmpty(), studyRule.explanation(), rules.schemaVersion());
    }

    private boolean resolved(String field, AnalysisResult analysis, Map<String, String> answers,
                             ObservationalStudyRuleRegistry.RuleSet rules) {
        var profile = analysis.profile();
        String profileValue = switch (field) {
            case "population" -> profile.population();
            case "exposure" -> profile.exposure();
            case "comparator" -> profile.comparator();
            case "outcome" -> profile.outcome();
            case "setting" -> profile.setting();
            case "timeFrame" -> profile.timeFrame();
            default -> null;
        };
        if (known(profileValue, rules.unresolvedValueMarkers())) return true;
        List<String> questionMarkers = rules.fields().get(field).answerQuestionMarkers();
        return answers.entrySet().stream()
                .anyMatch(entry -> questionMarkers.stream().anyMatch(entry.getKey()::contains)
                        && known(entry.getValue(), rules.unresolvedValueMarkers()));
    }

    private boolean known(String value, List<String> unresolvedMarkers) {
        return value != null && !value.isBlank()
                && unresolvedMarkers.stream().noneMatch(value::contains);
    }

    public record Assessment(StudyType studyType, List<String> requiredFields,
                             List<String> missingFields, boolean readyForDraft,
                             String explanation, String ruleVersion) {}
}
