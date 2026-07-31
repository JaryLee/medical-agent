package com.jarylee.medicalagent.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpertReviewRepository {
    Optional<ReviewTaskData> findByAgentTask(UUID hospitalId, UUID agentTaskId);

    Optional<ReviewTaskData> findLatestByProject(
            UUID hospitalId, UUID projectId);

    ReviewTaskData create(ReviewTaskData task);

    ReviewCommentData addComment(ReviewCommentData comment);

    List<ReviewCommentData> findComments(UUID hospitalId, UUID reviewTaskId);

    List<ReviewActionData> findActions(UUID hospitalId, UUID reviewTaskId);

    Optional<ReviewTaskData> decide(
            UUID hospitalId,
            UUID reviewTaskId,
            String responsibility,
            UUID reviewerId,
            String decision,
            String summary,
            String contentSha256,
            Instant decidedAt,
            long expectedVersion);

    Optional<ReviewTaskData> ownerConfirmAndLock(
            UUID hospitalId,
            UUID reviewTaskId,
            UUID ownerId,
            String contentSha256,
            Instant confirmedAt,
            long expectedVersion);

    Optional<ReviewTaskData> resetForNewRound(
            UUID hospitalId,
            UUID reviewTaskId,
            String contentSha256,
            Instant submittedAt,
            long expectedVersion);

    void addDecision(ReviewDecisionData decision);

    void addAction(ReviewActionData action);

    record ReviewTaskData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            UUID protocolId,
            UUID strobeCheckTaskId,
            String status,
            int roundNo,
            String contentSha256,
            boolean legacyReview,
            UUID submittedBy,
            Instant submittedAt,
            UUID expertReviewerId,
            String expertDecision,
            String expertSummary,
            Instant expertDecidedAt,
            UUID statisticalReviewerId,
            String statisticalDecision,
            String statisticalSummary,
            Instant statisticalDecidedAt,
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
            String responsibility,
            int reviewRoundNo,
            String content,
            UUID createdBy,
            Instant createdAt
    ) {}

    record ReviewActionData(
            UUID id,
            UUID hospitalId,
            UUID reviewTaskId,
            String actionType,
            int reviewRoundNo,
            UUID actorUserId,
            String summary,
            Instant occurredAt
    ) {}

    record ReviewDecisionData(
            UUID id,
            UUID hospitalId,
            UUID reviewTaskId,
            int reviewRoundNo,
            String responsibility,
            UUID reviewerId,
            String decision,
            String summary,
            String contentSha256,
            Instant decidedAt
    ) {}
}
