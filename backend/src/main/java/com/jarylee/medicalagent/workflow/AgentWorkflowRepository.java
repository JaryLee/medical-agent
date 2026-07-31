package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentWorkflowRepository {
    Optional<TaskData> findById(UUID hospitalId, UUID taskId);
    List<TaskData> findByProject(UUID hospitalId, UUID projectId);
    Optional<StepData> findLatestStep(
            UUID hospitalId, UUID taskId, String stepCode);
    Optional<TaskData> findByIdempotency(UUID hospitalId, UUID userId, String idempotencyKey);
    TaskData create(TaskData task, String idempotencyKey);
    List<TaskData> findRunnable(Instant now, int limit);
    List<TaskData> findTimedOut(Instant now, int limit);
    Optional<ClaimedTask> claimNext(
            Instant now, String leaseOwner, Duration leaseDuration);
    boolean heartbeat(ClaimHandle claim, Instant leaseUntil, Instant heartbeatAt);
    CommitOutcome commitClaim(
            ClaimHandle claim, List<StepData> steps, TaskTransition transition,
            List<PendingEvent> events, Instant committedAt);
    CommitOutcome failClaim(
            ClaimHandle claim, String errorCode, String errorMessage,
            PendingEvent event, Instant committedAt);
    CommitOutcome failTimedOut(
            UUID hospitalId, UUID taskId, long expectedVersion, Instant now,
            PendingEvent event);
    boolean claim(UUID hospitalId, UUID taskId, long expectedVersion, Instant leaseUntil);
    void waitForClarification(UUID hospitalId, UUID taskId, String outputJson);
    Optional<ClarificationRoundData> confirmClarifications(
            UUID hospitalId, UUID taskId, String sourceStep, String inputJson,
            String questionsJson, String answersJson, UUID confirmedBy,
            Instant confirmedAt, Instant timeoutAt);
    List<ClarificationRoundData> findClarificationRounds(UUID hospitalId, UUID taskId);
    void waitForConfirmation(UUID hospitalId, UUID taskId, String outputJson);
    boolean confirm(UUID hospitalId, UUID taskId, String inputJson, UUID confirmedBy,
                    Instant confirmedAt, Instant timeoutAt);
    void waitForSearchStrategyConfirmation(UUID hospitalId, UUID taskId, String outputJson);
    boolean confirmSearchStrategy(UUID hospitalId, UUID taskId, String taskOutputJson,
                                  String strategyOutputJson, UUID confirmedBy,
                                  Instant confirmedAt, Instant timeoutAt);
    void queueClinicalTrialsSearch(UUID hospitalId, UUID taskId, String outputJson);
    void queueLiteratureValidation(UUID hospitalId, UUID taskId, String outputJson);
    void queueSimilarResearchAnalysis(UUID hospitalId, UUID taskId, String outputJson);
    void queueObservationalDesignRecommendation(
            UUID hospitalId, UUID taskId, String outputJson);
    void waitForObservationalDesignConfirmation(
            UUID hospitalId, UUID taskId, String outputJson);
    boolean confirmObservationalDesign(
            UUID hospitalId, UUID taskId, String taskOutputJson,
            String recommendationOutputJson, UUID confirmedBy, Instant confirmedAt,
            Instant timeoutAt);
    void queueStatisticalDraft(UUID hospitalId, UUID taskId, String outputJson);
    void queueClaimCitationValidation(
            UUID hospitalId, UUID taskId, String outputJson);
    void queueStrobeCompletenessCheck(
            UUID hospitalId, UUID taskId, String outputJson);
    void waitForExpertReview(UUID hospitalId, UUID taskId, String outputJson);
    boolean markExpertReviewReturned(UUID hospitalId, UUID taskId);
    boolean updateProtocolRevisionOutput(
            UUID hospitalId, UUID taskId, long expectedVersion,
            String outputJson, Instant updatedAt);
    boolean advanceToExport(
            UUID hospitalId, UUID taskId, UUID confirmedBy, Instant confirmedAt);
    boolean completeExport(
            UUID hospitalId, UUID taskId, String outputJson,
            UUID confirmedBy, Instant confirmedAt);
    void complete(UUID hospitalId, UUID taskId, String outputJson, Instant completedAt);
    void fail(UUID hospitalId, UUID taskId, String errorCode, String errorMessage);
    boolean cancel(UUID hospitalId, UUID taskId, Instant completedAt);
    boolean retry(UUID hospitalId, UUID taskId, Instant timeoutAt);
    void saveStep(StepData step);
    EventData appendEvent(UUID hospitalId, UUID taskId, String eventType,
                          String stepCode, String payloadJson, Instant occurredAt);
    List<EventData> findEventsAfter(UUID hospitalId, UUID taskId, long afterEventId);

    record TaskData(UUID id, UUID hospitalId, UUID projectId, UUID createdBy,
                    String currentStep, String status, String inputJson, String outputJson,
                    Instant leaseUntil, Instant timeoutAt, boolean cancelRequested,
                    long version, String lastErrorCode, String lastErrorMessage,
                    Instant createdAt, Instant updatedAt, Instant completedAt) {}

    record StepData(UUID id, UUID hospitalId, UUID taskId, String stepCode, int attemptNo,
                    String status, String inputSchemaVersion, String outputSchemaVersion,
                    String inputJson, String outputJson, UUID modelCallId, String promptVersion,
                    String toolCallsJson, String errorCode, String errorMessage,
                    Instant startedAt, Instant completedAt, boolean requiresConfirmation,
                    UUID confirmedBy, Instant confirmedAt) {}

    record EventData(long id, UUID hospitalId, UUID taskId, String eventType,
                     String stepCode, String payloadJson, Instant occurredAt) {}

    record ClarificationRoundData(UUID id, UUID hospitalId, UUID taskId, int roundNo,
                                  String sourceStep, String questionsJson, String answersJson,
                                  UUID submittedBy, Instant submittedAt) {}

    record ClaimHandle(
            UUID hospitalId,
            UUID taskId,
            String stepCode,
            UUID executionToken,
            UUID stepAttemptId,
            int attemptNo,
            String leaseOwner) {}

    record ClaimedTask(TaskData task, ClaimHandle claim, List<EventData> events) {
        public ClaimedTask {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    record TaskTransition(
            String nextStep, String nextStatus, String outputJson,
            Instant completedAt) {}

    record PendingEvent(
            String stableKey, String eventType, String stepCode,
            String payloadJson) {}

    enum CommitStatus {
        APPLIED,
        ALREADY_APPLIED,
        STALE_TOKEN
    }

    record CommitOutcome(
            CommitStatus status, TaskData task, List<EventData> events) {
        public CommitOutcome {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }
}
