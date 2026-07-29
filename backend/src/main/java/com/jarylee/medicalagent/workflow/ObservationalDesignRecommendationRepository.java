package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ObservationalDesignRecommendationRepository {
    void create(RecommendationData recommendation);

    void complete(
            RecommendationData recommendation,
            List<ObservationalDesignRecommendationModels.DesignAlternative> alternatives);

    void fail(
            UUID hospitalId, UUID recommendationId, String errorCode,
            String errorMessage, Instant completedAt);

    record RecommendationData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            String status,
            Instant startedAt,
            Instant completedAt,
            String recommendedStudyType,
            String primaryOutcomeCandidate,
            Boolean readyForProtocolDraft,
            Integer alternativeCount,
            String unresolvedItemsJson,
            String requiredConfirmationsJson,
            String inputSha256,
            String algorithmVersion,
            String resultJson,
            String errorCode,
            String errorMessage
    ) {}
}
