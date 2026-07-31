package com.jarylee.medicalagent.review;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryExpertReviewRepository implements ExpertReviewRepository {
    private final Map<UUID, ReviewTaskData> tasks = new ConcurrentHashMap<>();
    private final List<ReviewCommentData> comments = new ArrayList<>();
    private final List<ReviewActionData> actions = new ArrayList<>();
    private final List<ReviewDecisionData> decisions = new ArrayList<>();

    @Override
    public Optional<ReviewTaskData> findByAgentTask(UUID hospitalId, UUID agentTaskId) {
        return tasks.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.agentTaskId().equals(agentTaskId))
                .findFirst();
    }

    @Override
    public Optional<ReviewTaskData> findLatestByProject(
            UUID hospitalId, UUID projectId) {
        return tasks.values().stream()
                .filter(value ->
                        value.hospitalId().equals(hospitalId)
                                && value.projectId().equals(projectId))
                .max(Comparator
                        .comparingInt(ReviewTaskData::roundNo)
                        .thenComparing(ReviewTaskData::submittedAt)
                        .thenComparing(ReviewTaskData::id));
    }

    @Override
    public synchronized ReviewTaskData create(ReviewTaskData task) {
        var existing = findByAgentTask(task.hospitalId(), task.agentTaskId());
        if (existing.isPresent()) return existing.get();
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public synchronized ReviewCommentData addComment(ReviewCommentData comment) {
        comments.add(comment);
        return comment;
    }

    @Override
    public synchronized List<ReviewCommentData> findComments(
            UUID hospitalId, UUID reviewTaskId) {
        return comments.stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.reviewTaskId().equals(reviewTaskId))
                .sorted(Comparator.comparing(ReviewCommentData::createdAt))
                .toList();
    }

    @Override
    public synchronized List<ReviewActionData> findActions(
            UUID hospitalId, UUID reviewTaskId) {
        return actions.stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.reviewTaskId().equals(reviewTaskId))
                .sorted(Comparator.comparing(ReviewActionData::occurredAt))
                .toList();
    }

    @Override
    public synchronized Optional<ReviewTaskData> decide(
            UUID hospitalId, UUID reviewTaskId, String responsibility,
            UUID reviewerId, String decision, String summary,
            String contentSha256, java.time.Instant decidedAt,
            long expectedVersion) {
        var current = tasks.get(reviewTaskId);
        if (current == null || !current.hospitalId().equals(hospitalId)
                || !"WAITING_EXPERT_REVIEW".equals(current.status())
                || !current.contentSha256().equals(contentSha256)
                || current.submittedBy().equals(reviewerId)
                || current.version() != expectedVersion) return Optional.empty();
        boolean medical = "MEDICAL_REVIEW".equals(responsibility);
        UUID currentReviewer = medical
                ? current.expertReviewerId()
                : current.statisticalReviewerId();
        UUID otherReviewer = medical
                ? current.statisticalReviewerId()
                : current.expertReviewerId();
        String currentDecision = medical
                ? current.expertDecision()
                : current.statisticalDecision();
        if ((currentReviewer != null && !currentReviewer.equals(reviewerId))
                || currentDecision != null
                || (otherReviewer != null && otherReviewer.equals(reviewerId))) {
            return Optional.empty();
        }
        String otherDecision = medical
                ? current.statisticalDecision()
                : current.expertDecision();
        String status = "RETURN_FOR_REVISION".equals(decision)
                ? "REVISION_REQUIRED"
                : "APPROVE".equals(otherDecision)
                    ? "EXPERT_APPROVED"
                    : "WAITING_EXPERT_REVIEW";
        var updated = new ReviewTaskData(
                current.id(), current.hospitalId(), current.projectId(),
                current.agentTaskId(), current.protocolId(), current.strobeCheckTaskId(),
                status, current.roundNo(), current.contentSha256(),
                current.legacyReview(), current.submittedBy(), current.submittedAt(),
                medical ? reviewerId : current.expertReviewerId(),
                medical ? decision : current.expertDecision(),
                medical ? summary : current.expertSummary(),
                medical ? decidedAt : current.expertDecidedAt(),
                medical ? current.statisticalReviewerId() : reviewerId,
                medical ? current.statisticalDecision() : decision,
                medical ? current.statisticalSummary() : summary,
                medical ? current.statisticalDecidedAt() : decidedAt,
                null, null, false, current.version() + 1);
        tasks.put(reviewTaskId, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<ReviewTaskData> ownerConfirmAndLock(
            UUID hospitalId, UUID reviewTaskId, UUID ownerId,
            String contentSha256, java.time.Instant confirmedAt,
            long expectedVersion) {
        var current = tasks.get(reviewTaskId);
        if (current == null || !current.hospitalId().equals(hospitalId)
                || !"EXPERT_APPROVED".equals(current.status())
                || !"APPROVE".equals(current.expertDecision())
                || !"APPROVE".equals(current.statisticalDecision())
                || !current.contentSha256().equals(contentSha256)
                || ownerId.equals(current.expertReviewerId())
                || ownerId.equals(current.statisticalReviewerId())
                || current.version() != expectedVersion) return Optional.empty();
        var updated = new ReviewTaskData(
                current.id(), current.hospitalId(), current.projectId(),
                current.agentTaskId(), current.protocolId(), current.strobeCheckTaskId(),
                "APPROVED", current.roundNo(), current.contentSha256(),
                current.legacyReview(), current.submittedBy(), current.submittedAt(),
                current.expertReviewerId(), current.expertDecision(),
                current.expertSummary(), current.expertDecidedAt(),
                current.statisticalReviewerId(), current.statisticalDecision(),
                current.statisticalSummary(), current.statisticalDecidedAt(), ownerId,
                confirmedAt, true, current.version() + 1);
        tasks.put(reviewTaskId, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized void addAction(ReviewActionData action) {
        actions.add(action);
    }

    @Override
    public synchronized Optional<ReviewTaskData> resetForNewRound(
            UUID hospitalId, UUID reviewTaskId, String contentSha256,
            java.time.Instant submittedAt, long expectedVersion) {
        var current = tasks.get(reviewTaskId);
        if (current == null || !current.hospitalId().equals(hospitalId)
                || current.version() != expectedVersion
                || (!"SUPERSEDED".equals(current.status())
                && current.contentSha256().equals(contentSha256))) {
            return Optional.empty();
        }
        var updated = new ReviewTaskData(
                current.id(), current.hospitalId(), current.projectId(),
                current.agentTaskId(), current.protocolId(),
                current.strobeCheckTaskId(), "WAITING_EXPERT_REVIEW",
                current.roundNo() + 1, contentSha256, false,
                current.submittedBy(), submittedAt,
                null, null, null, null,
                null, null, null, null,
                null, null, false, current.version() + 1);
        tasks.put(reviewTaskId, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized void addDecision(ReviewDecisionData decision) {
        decisions.add(decision);
    }
}
