package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ObservationalDesignRecommendationModels {
    private ObservationalDesignRecommendationModels() {}

    public record DesignAlternative(
            int rank,
            StudyType studyType,
            int score,
            String feasibilityStatus,
            String rationale,
            List<String> requiredFields,
            List<String> missingFields,
            List<String> biasRisks,
            List<String> evidenceConsiderations
    ) {}

    public record Recommendation(
            String schemaVersion,
            UUID recommendationTaskId,
            Instant recommendedAt,
            StudyType recommendedStudyType,
            String primaryOutcomeCandidate,
            List<DesignAlternative> alternatives,
            boolean readyForProtocolDraft,
            List<String> unresolvedItems,
            List<String> requiredConfirmations,
            String confirmationStatus,
            StudyType confirmedStudyType,
            String confirmedPrimaryOutcome,
            boolean protocolGenerationAuthorized,
            UUID confirmedBy,
            Instant confirmedAt,
            String inputSha256,
            String algorithmVersion,
            List<String> limitations
    ) {}

    public record Confirmation(
            StudyType studyType,
            String primaryOutcome,
            boolean authorizeProtocolGeneration
    ) {}
}
