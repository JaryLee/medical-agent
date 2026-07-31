package com.jarylee.medicalagent.agent.model;

import java.util.List;

public final class ObservationalDesignModel {
    public static final String INPUT_SCHEMA =
            "observational-design-model-advice-input/v1";
    public static final String OUTPUT_SCHEMA =
            "observational-design-model-advice/v1";

    private ObservationalDesignModel() {}

    public record AdviceRequest(
            String schemaVersion,
            String ruleVersion,
            String recommendedStudyType,
            String primaryOutcomeCandidate,
            List<RuleAlternative> alternatives,
            List<String> unresolvedItems,
            List<String> requiredConfirmations
    ) {}

    public record RuleAlternative(
            String studyType,
            int rank,
            int score,
            String feasibilityStatus,
            List<String> missingFields,
            List<String> biasRisks
    ) {}

    public record Advice(
            String schemaVersion,
            String selectedStudyType,
            String alignment,
            String rationale,
            List<String> biasConsiderations,
            List<String> missingFields,
            List<String> suggestedConfirmations,
            List<String> limitations,
            boolean advisoryOnly
    ) {}
}
