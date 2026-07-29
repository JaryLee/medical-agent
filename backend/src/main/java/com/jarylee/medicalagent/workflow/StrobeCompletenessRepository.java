package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrobeCompletenessRepository {
    Optional<CheckTaskData> findByAgentTask(UUID hospitalId, UUID agentTaskId);

    void save(CheckTaskData task, List<StrobeCompletenessModels.CheckItem> items);

    record CheckTaskData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            UUID protocolId,
            String status,
            String studyType,
            int totalItemCount,
            int coveredCount,
            int partiallyCoveredCount,
            int missingCount,
            int notApplicableCount,
            int needsExpertReviewCount,
            String inputSha256,
            String checkerVersion,
            String resultJson,
            Instant createdAt
    ) {}
}
