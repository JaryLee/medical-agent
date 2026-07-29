package com.jarylee.medicalagent.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ExpertReviewModels {
    private ExpertReviewModels() {}

    public enum Decision {
        APPROVE,
        RETURN_FOR_REVISION
    }

    public enum CommentType {
        MEDICAL,
        STATISTICAL,
        REPORTING,
        GENERAL
    }

    public record ReviewComment(
            UUID id,
            UUID protocolSectionId,
            Integer protocolSectionVersionNo,
            UUID strobeItemResultId,
            CommentType commentType,
            String content,
            UUID createdBy,
            Instant createdAt
    ) {}

    public record ReviewAction(
            UUID id,
            String actionType,
            UUID actorUserId,
            String summary,
            Instant occurredAt
    ) {}

    public record ReviewView(
            UUID reviewTaskId,
            UUID projectId,
            UUID agentTaskId,
            UUID protocolId,
            UUID strobeCheckTaskId,
            String status,
            UUID submittedBy,
            Instant submittedAt,
            UUID expertReviewerId,
            Decision expertDecision,
            String expertSummary,
            Instant expertDecidedAt,
            UUID ownerConfirmedBy,
            Instant ownerConfirmedAt,
            boolean sectionsLocked,
            long version,
            List<ReviewComment> comments,
            List<ReviewAction> history
    ) {}
}
