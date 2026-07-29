package com.jarylee.medicalagent.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpertReviewRepository {
    Optional<ReviewTaskData> findByAgentTask(UUID hospitalId, UUID agentTaskId);

    ReviewTaskData create(ReviewTaskData task);

    ReviewCommentData addComment(ReviewCommentData comment);

    List<ReviewCommentData> findComments(UUID hospitalId, UUID reviewTaskId);

    List<ReviewActionData> findActions(UUID hospitalId, UUID reviewTaskId);

    Optional<ReviewTaskData> decide(
            UUID hospitalId,
            UUID reviewTaskId,
            UUID reviewerId,
            String decision,
            String summary,
            Instant decidedAt,
            long expectedVersion);

    Optional<ReviewTaskData> ownerConfirmAndLock(
            UUID hospitalId,
            UUID reviewTaskId,
            UUID ownerId,
            Instant confirmedAt,
            long expectedVersion);

    void addAction(ReviewActionData action);

    record ReviewTaskData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            UUID protocolId,
            UUID strobeCheckTaskId,
            String status,
            UUID submittedBy,
            Instant submittedAt,
            UUID expertReviewerId,
            String expertDecision,
            String expertSummary,
            Instant expertDecidedAt,
            UUID ownerConfirmedBy,
            Instant ownerConfirmedAt,
            boolean sectionsLocked,
            long version
    ) {}

    record ReviewCommentData(
            UUID id,
            UUID hospitalId,
            UUID reviewTaskId,
            UUID protocolSectionId,
            Integer protocolSectionVersionNo,
            UUID strobeItemResultId,
            String commentType,
            String content,
            UUID createdBy,
            Instant createdAt
    ) {}

    record ReviewActionData(
            UUID id,
            UUID hospitalId,
            UUID reviewTaskId,
            String actionType,
            UUID actorUserId,
            String summary,
            Instant occurredAt
    ) {}
}
