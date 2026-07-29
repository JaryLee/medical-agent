package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimCitationValidationRepository {
    Optional<ValidationTaskData> findByAgentTask(UUID hospitalId, UUID agentTaskId);

    void save(
            ValidationTaskData task,
            List<ClaimCitationValidationModels.ResearchClaim> claims);

    record ValidationTaskData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            UUID protocolId,
            String status,
            int claimCount,
            int citationLinkCount,
            int abstractOnlyClaimCount,
            int needsExpertReviewClaimCount,
            String inputSha256,
            String validatorVersion,
            String resultJson,
            Instant createdAt
    ) {}
}
