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

    @Override
    public Optional<ReviewTaskData> findByAgentTask(UUID hospitalId, UUID agentTaskId) {
        return tasks.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.agentTaskId().equals(agentTaskId))
                .findFirst();
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
            UUID hospitalId, UUID reviewTaskId, UUID reviewerId, String decision,
            String summary, java.time.Instant decidedAt, long expectedVersion) {
        var current = tasks.get(reviewTaskId);
        if (current == null || !current.hospitalId().equals(hospitalId)
                || !"WAITING_EXPERT_REVIEW".equals(current.status())
                || current.version() != expectedVersion) return Optional.empty();
        String status = "APPROVE".equals(decision)
                ? "EXPERT_APPROVED" : "REVISION_REQUIRED";
        var updated = new ReviewTaskData(
                current.id(), current.hospitalId(), current.projectId(),
                current.agentTaskId(), current.protocolId(), current.strobeCheckTaskId(),
                status, current.submittedBy(), current.submittedAt(), reviewerId,
                decision, summary, decidedAt, null, null, false, current.version() + 1);
        tasks.put(reviewTaskId, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<ReviewTaskData> ownerConfirmAndLock(
            UUID hospitalId, UUID reviewTaskId, UUID ownerId,
            java.time.Instant confirmedAt, long expectedVersion) {
        var current = tasks.get(reviewTaskId);
        if (current == null || !current.hospitalId().equals(hospitalId)
                || !"EXPERT_APPROVED".equals(current.status())
                || current.version() != expectedVersion) return Optional.empty();
        var updated = new ReviewTaskData(
                current.id(), current.hospitalId(), current.projectId(),
                current.agentTaskId(), current.protocolId(), current.strobeCheckTaskId(),
                "APPROVED", current.submittedBy(), current.submittedAt(),
                current.expertReviewerId(), current.expertDecision(),
                current.expertSummary(), current.expertDecidedAt(), ownerId,
                confirmedAt, true, current.version() + 1);
        tasks.put(reviewTaskId, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized void addAction(ReviewActionData action) {
        actions.add(action);
    }
}
