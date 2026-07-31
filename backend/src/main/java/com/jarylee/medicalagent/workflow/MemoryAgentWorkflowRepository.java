package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.workspace.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("memory")
public class MemoryAgentWorkflowRepository implements AgentWorkflowRepository {
    private final WorkspaceRepository workspace;
    private final Map<UUID, TaskData> tasks = new ConcurrentHashMap<>();
    private final Map<String, UUID> idempotency = new ConcurrentHashMap<>();
    private final List<StepData> steps = new ArrayList<>();
    private final List<EventData> events = new ArrayList<>();
    private final List<ClarificationRoundData> clarificationRounds = new ArrayList<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final Map<UUID, ClaimHandle> activeClaims = new ConcurrentHashMap<>();
    private final Map<String, Integer> attemptSequences = new ConcurrentHashMap<>();
    private final Map<UUID, String> attemptStatuses = new ConcurrentHashMap<>();
    private final Map<String, EventData> eventsByStableKey = new ConcurrentHashMap<>();

    public MemoryAgentWorkflowRepository() {
        this.workspace = null;
    }

    @Autowired
    public MemoryAgentWorkflowRepository(WorkspaceRepository workspace) {
        this.workspace = workspace;
    }

    @Override
    public Optional<TaskData> findById(UUID hospitalId, UUID taskId) {
        return Optional.ofNullable(tasks.get(taskId))
                .filter(task -> task.hospitalId().equals(hospitalId));
    }

    @Override
    public List<TaskData> findByProject(UUID hospitalId, UUID projectId) {
        return tasks.values().stream()
                .filter(task -> task.hospitalId().equals(hospitalId)
                        && task.projectId().equals(projectId))
                .sorted(Comparator.comparing(TaskData::createdAt)
                        .thenComparing(TaskData::id)
                        .reversed())
                .toList();
    }

    @Override
    public synchronized Optional<StepData> findLatestStep(
            UUID hospitalId, UUID taskId, String stepCode) {
        return steps.stream()
                .filter(step -> step.hospitalId().equals(hospitalId)
                        && step.taskId().equals(taskId)
                        && step.stepCode().equals(stepCode))
                .max(Comparator.comparingInt(StepData::attemptNo)
                        .thenComparing(StepData::startedAt));
    }

    @Override
    public Optional<TaskData> findByIdempotency(UUID hospitalId, UUID userId, String key) {
        UUID id = idempotency.get(hospitalId + ":" + userId + ":" + key);
        return id == null ? Optional.empty() : findById(hospitalId, id);
    }

    @Override
    public synchronized TaskData create(TaskData task, String key) {
        String scope = task.hospitalId() + ":" + task.createdBy() + ":" + key;
        UUID existing = idempotency.putIfAbsent(scope, task.id());
        if (existing != null) return tasks.get(existing);
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public List<TaskData> findRunnable(Instant now, int limit) {
        return tasks.values().stream()
                .filter(task -> !task.cancelRequested() && task.timeoutAt().isAfter(now))
                .filter(task -> "QUEUED".equals(task.status())
                        || ("RUNNING".equals(task.status())
                        && (task.leaseUntil() == null || task.leaseUntil().isBefore(now))))
                .sorted(Comparator.comparing(TaskData::createdAt))
                .limit(limit).toList();
    }

    @Override
    public List<TaskData> findTimedOut(Instant now, int limit) {
        return tasks.values().stream()
                .filter(task -> List.of("QUEUED", "RUNNING").contains(task.status()))
                .filter(task -> !task.cancelRequested() && !task.timeoutAt().isAfter(now))
                .limit(limit).toList();
    }

    @Override
    public synchronized Optional<ClaimedTask> claimNext(
            Instant now, String leaseOwner, Duration leaseDuration) {
        Optional<TaskData> candidate = findRunnable(now, 1).stream().findFirst();
        if (candidate.isEmpty()) return Optional.empty();
        TaskData task = candidate.get();
        UUID token = UUID.randomUUID();
        String attemptKey = task.id() + "|" + task.currentStep();
        int attemptNo = attemptSequences.merge(attemptKey, 1, Integer::sum);
        ClaimHandle handle = new ClaimHandle(
                task.hospitalId(), task.id(), task.currentStep(), token,
                UUID.randomUUID(), attemptNo, leaseOwner);
        ClaimHandle previous = activeClaims.get(task.id());
        if (previous != null) {
            attemptStatuses.put(previous.stepAttemptId(), "LEASE_LOST");
        }
        TaskData claimed = copy(
                task, task.currentStep(), "RUNNING", task.inputJson(), task.outputJson(),
                now.plus(leaseDuration), task.timeoutAt(), task.cancelRequested(),
                task.version() + 1, null, null, task.completedAt());
        tasks.put(task.id(), claimed);
        activeClaims.put(task.id(), handle);
        attemptStatuses.put(handle.stepAttemptId(), "RUNNING");
        List<EventData> started = appendPendingEvents(
                task.hospitalId(), task.id(),
                List.of(new PendingEvent(
                        handle.stepAttemptId() + ":started", "TASK_STARTED",
                        task.currentStep(), "{\"status\":\"RUNNING\"}")),
                now);
        return Optional.of(new ClaimedTask(claimed, handle, started));
    }

    @Override
    public synchronized boolean heartbeat(
            ClaimHandle claim, Instant leaseUntil, Instant heartbeatAt) {
        ClaimHandle current = activeClaims.get(claim.taskId());
        TaskData task = tasks.get(claim.taskId());
        if (!claim.equals(current) || task == null || !"RUNNING".equals(task.status())) {
            return false;
        }
        tasks.put(task.id(), copy(
                task, task.currentStep(), task.status(), task.inputJson(), task.outputJson(),
                leaseUntil, task.timeoutAt(), task.cancelRequested(), task.version(),
                task.lastErrorCode(), task.lastErrorMessage(), task.completedAt()));
        return true;
    }

    @Override
    public synchronized CommitOutcome commitClaim(
            ClaimHandle claim, List<StepData> committedSteps,
            TaskTransition transition, List<PendingEvent> pendingEvents,
            Instant committedAt) {
        if (!claim.equals(activeClaims.get(claim.taskId()))) {
            return completedOrStale(claim, "COMPLETED", pendingEvents);
        }
        TaskData task = tasks.get(claim.taskId());
        if (task == null || !task.hospitalId().equals(claim.hospitalId())
                || !"RUNNING".equals(task.status())
                || !claim.stepCode().equals(task.currentStep())
                || task.cancelRequested()) {
            return new CommitOutcome(CommitStatus.STALE_TOKEN, task, List.of());
        }
        steps.addAll(committedSteps);
        attemptStatuses.put(claim.stepAttemptId(), "COMPLETED");
        activeClaims.remove(claim.taskId(), claim);
        TaskData current = copy(
                task, transition.nextStep(), transition.nextStatus(),
                task.inputJson(), transition.outputJson(), null, task.timeoutAt(),
                false, task.version() + 1, null, null, transition.completedAt());
        tasks.put(task.id(), current);
        return new CommitOutcome(
                CommitStatus.APPLIED, current,
                appendPendingEvents(claim.hospitalId(), claim.taskId(),
                        pendingEvents, committedAt));
    }

    @Override
    public synchronized CommitOutcome failClaim(
            ClaimHandle claim, String errorCode, String errorMessage,
            PendingEvent event, Instant committedAt) {
        if (!claim.equals(activeClaims.get(claim.taskId()))) {
            return completedOrStale(claim, "FAILED", List.of(event));
        }
        TaskData task = tasks.get(claim.taskId());
        if (task == null || task.cancelRequested() || !"RUNNING".equals(task.status())) {
            return new CommitOutcome(CommitStatus.STALE_TOKEN, task, List.of());
        }
        attemptStatuses.put(claim.stepAttemptId(), "FAILED");
        activeClaims.remove(claim.taskId(), claim);
        TaskData current = copy(
                task, task.currentStep(), "FAILED", task.inputJson(), task.outputJson(),
                null, task.timeoutAt(), false, task.version() + 1,
                errorCode, truncate(errorMessage), task.completedAt());
        tasks.put(task.id(), current);
        return new CommitOutcome(
                CommitStatus.APPLIED, current,
                appendPendingEvents(claim.hospitalId(), claim.taskId(),
                        List.of(event), committedAt));
    }

    @Override
    public synchronized CommitOutcome failTimedOut(
            UUID hospitalId, UUID taskId, long expectedVersion, Instant now,
            PendingEvent event) {
        TaskData task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || task.version() != expectedVersion || task.cancelRequested()
                || task.timeoutAt().isAfter(now)
                || !List.of("QUEUED", "RUNNING").contains(task.status())) {
            return new CommitOutcome(CommitStatus.STALE_TOKEN, task, List.of());
        }
        ClaimHandle claim = activeClaims.remove(taskId);
        if (claim != null) {
            attemptStatuses.put(claim.stepAttemptId(), "FAILED");
        }
        TaskData current = copy(
                task, task.currentStep(), "FAILED", task.inputJson(), task.outputJson(),
                null, task.timeoutAt(), false, task.version() + 1,
                "TASK_TIMEOUT", "Agent任务执行超时", task.completedAt());
        tasks.put(taskId, current);
        return new CommitOutcome(
                CommitStatus.APPLIED, current,
                appendPendingEvents(hospitalId, taskId, List.of(event), now));
    }

    @Override
    public synchronized boolean claim(UUID hospitalId, UUID taskId, long version, Instant leaseUntil) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId) || task.version() != version) return false;
        tasks.put(taskId, copy(task, task.currentStep(), "RUNNING", task.inputJson(), task.outputJson(),
                leaseUntil, task.timeoutAt(), false, version + 1,
                task.lastErrorCode(), task.lastErrorMessage(), task.completedAt()));
        return true;
    }

    @Override
    public synchronized void waitForClarification(UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(task, "STEP_03_ASK_CLARIFICATION",
                "WAITING_CONFIRMATION", task.inputJson(), outputJson, null, task.timeoutAt(),
                false, task.version() + 1, null, null, null));
    }

    @Override
    public synchronized Optional<ClarificationRoundData> confirmClarifications(
            UUID hospitalId, UUID taskId, String sourceStep, String inputJson,
            String questionsJson, String answersJson, UUID confirmedBy,
            Instant confirmedAt, Instant timeoutAt) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || !"WAITING_CONFIRMATION".equals(task.status())
                || !sourceStep.equals(task.currentStep())
                || !List.of("STEP_03_ASK_CLARIFICATION", "STEP_05_CONFIRM_DIRECTION")
                .contains(sourceStep)) return Optional.empty();
        tasks.put(taskId, copy(task, "STEP_04_GENERATE_RESEARCH_DIRECTIONS", "QUEUED",
                inputJson, task.outputJson(), null, timeoutAt, false,
                task.version() + 1, null, null, null));
        finishClarificationStep(taskId, sourceStep, confirmedBy, confirmedAt);
        int roundNo = clarificationRounds.stream()
                .filter(round -> round.taskId().equals(taskId))
                .mapToInt(ClarificationRoundData::roundNo).max().orElse(0) + 1;
        var round = new ClarificationRoundData(
                UUID.randomUUID(), hospitalId, taskId, roundNo, sourceStep,
                questionsJson, answersJson, confirmedBy, confirmedAt);
        clarificationRounds.add(round);
        return Optional.of(round);
    }

    @Override
    public synchronized List<ClarificationRoundData> findClarificationRounds(
            UUID hospitalId, UUID taskId) {
        return clarificationRounds.stream()
                .filter(round -> round.hospitalId().equals(hospitalId)
                        && round.taskId().equals(taskId))
                .sorted(Comparator.comparingInt(ClarificationRoundData::roundNo))
                .toList();
    }

    @Override
    public synchronized void waitForConfirmation(UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(task, "STEP_05_CONFIRM_DIRECTION",
                "WAITING_CONFIRMATION", task.inputJson(), outputJson, null, task.timeoutAt(),
                false, task.version() + 1, null, null, null));
    }

    @Override
    public synchronized boolean confirm(UUID hospitalId, UUID taskId, String inputJson,
                                        UUID confirmedBy, Instant confirmedAt, Instant timeoutAt) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || !"WAITING_CONFIRMATION".equals(task.status())
                || !"STEP_05_CONFIRM_DIRECTION".equals(task.currentStep())) return false;
        tasks.put(taskId, copy(task, "STEP_05_CONFIRM_DIRECTION", "QUEUED", inputJson,
                task.outputJson(), null, timeoutAt, false, task.version() + 1,
                null, null, null));
        completeConfirmationStep(taskId, "STEP_05_CONFIRM_DIRECTION", confirmedBy, confirmedAt);
        return true;
    }

    @Override
    public synchronized void waitForSearchStrategyConfirmation(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(task, "STEP_07_BUILD_SEARCH_STRATEGY",
                "WAITING_CONFIRMATION", task.inputJson(), outputJson, null, task.timeoutAt(),
                false, task.version() + 1, null, null, null));
    }

    @Override
    public synchronized boolean confirmSearchStrategy(
            UUID hospitalId, UUID taskId, String taskOutputJson, String strategyOutputJson,
            UUID confirmedBy, Instant confirmedAt, Instant timeoutAt) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || !"WAITING_CONFIRMATION".equals(task.status())
                || !"STEP_07_BUILD_SEARCH_STRATEGY".equals(task.currentStep())) return false;
        tasks.put(taskId, copy(task, "STEP_08_SEARCH_PUBMED", "QUEUED",
                task.inputJson(), taskOutputJson, null, timeoutAt, false,
                task.version() + 1, null, null, null));
        completeConfirmationStep(
                taskId, "STEP_07_BUILD_SEARCH_STRATEGY",
                strategyOutputJson, confirmedBy, confirmedAt);
        return true;
    }

    @Override
    public synchronized void queueClinicalTrialsSearch(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(task, "STEP_09_SEARCH_CLINICAL_TRIALS",
                "QUEUED", task.inputJson(), outputJson, null, task.timeoutAt(),
                false, task.version() + 1, null, null, null));
    }

    @Override
    public synchronized void queueLiteratureValidation(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(task, "STEP_10_VALIDATE_LITERATURE",
                "QUEUED", task.inputJson(), outputJson, null, task.timeoutAt(),
                false, task.version() + 1, null, null, null));
    }

    @Override
    public synchronized void queueSimilarResearchAnalysis(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(task, "STEP_11_ANALYZE_SIMILAR_RESEARCH",
                "QUEUED", task.inputJson(), outputJson, null, task.timeoutAt(),
                false, task.version() + 1, null, null, null));
    }

    @Override
    public synchronized void queueObservationalDesignRecommendation(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(
                task, "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN",
                "QUEUED", task.inputJson(), outputJson, null, task.timeoutAt(),
                false, task.version() + 1, null, null, null));
    }

    @Override
    public synchronized void waitForObservationalDesignConfirmation(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(
                task, "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN",
                "WAITING_CONFIRMATION", task.inputJson(), outputJson, null,
                task.timeoutAt(), false, task.version() + 1, null, null, null));
    }

    @Override
    public synchronized boolean confirmObservationalDesign(
            UUID hospitalId, UUID taskId, String taskOutputJson,
            String recommendationOutputJson, UUID confirmedBy, Instant confirmedAt,
            Instant timeoutAt) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || !"WAITING_CONFIRMATION".equals(task.status())
                || !"STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN".equals(task.currentStep())) {
            return false;
        }
        tasks.put(taskId, copy(
                task, "STEP_13_GENERATE_PROTOCOL_SECTIONS", "QUEUED",
                task.inputJson(), taskOutputJson, null, timeoutAt, false,
                task.version() + 1, null, null, null));
        completeConfirmationStep(
                taskId, "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN",
                recommendationOutputJson, confirmedBy, confirmedAt);
        return true;
    }

    @Override
    public synchronized void queueStatisticalDraft(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(
                task, "STEP_14_GENERATE_STATISTICAL_DRAFT", "QUEUED",
                task.inputJson(), outputJson, null, task.timeoutAt(), false,
                task.version() + 1, null, null, null));
    }

    @Override
    public synchronized void queueClaimCitationValidation(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(
                task, "STEP_15_VALIDATE_CLAIMS_AND_CITATIONS", "QUEUED",
                task.inputJson(), outputJson, null, task.timeoutAt(), false,
                task.version() + 1, null, null, null));
    }

    @Override
    public synchronized void queueStrobeCompletenessCheck(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(
                task, "STEP_16_CHECK_STROBE_COMPLETENESS", "QUEUED",
                task.inputJson(), outputJson, null, task.timeoutAt(), false,
                task.version() + 1, null, null, null));
    }

    @Override
    public synchronized void waitForExpertReview(
            UUID hospitalId, UUID taskId, String outputJson) {
        update(hospitalId, taskId, task -> copy(
                task, "STEP_17_WAIT_EXPERT_REVIEW", "WAITING_CONFIRMATION",
                task.inputJson(), outputJson, null, task.timeoutAt(), false,
                task.version() + 1, null, null, null));
    }

    @Override
    public synchronized boolean markExpertReviewReturned(
            UUID hospitalId, UUID taskId) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || !"STEP_17_WAIT_EXPERT_REVIEW".equals(task.currentStep())
                || !"WAITING_CONFIRMATION".equals(task.status())) return false;
        update(hospitalId, taskId, value -> copy(
                value, value.currentStep(), "REVISION_REQUIRED",
                value.inputJson(), value.outputJson(), null, value.timeoutAt(), false,
                value.version() + 1, null, null, null));
        return true;
    }

    @Override
    public synchronized boolean updateProtocolRevisionOutput(
            UUID hospitalId,
            UUID taskId,
            long expectedVersion,
            String outputJson,
            Instant updatedAt) {
        var task = tasks.get(taskId);
        if (task == null
                || !task.hospitalId().equals(hospitalId)
                || task.version() != expectedVersion
                || !"STEP_17_WAIT_EXPERT_REVIEW".equals(
                task.currentStep())
                || !"REVISION_REQUIRED".equals(task.status())) {
            return false;
        }
        tasks.put(taskId, copy(
                task, task.currentStep(), task.status(),
                task.inputJson(), outputJson, null,
                task.timeoutAt(), false, task.version() + 1,
                null, null, null));
        return true;
    }

    @Override
    public synchronized boolean advanceToExport(
            UUID hospitalId, UUID taskId, UUID confirmedBy, Instant confirmedAt) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || !"STEP_17_WAIT_EXPERT_REVIEW".equals(task.currentStep())
                || !"WAITING_CONFIRMATION".equals(task.status())) return false;
        completeConfirmationStep(
                taskId, "STEP_17_WAIT_EXPERT_REVIEW", confirmedBy, confirmedAt);
        update(hospitalId, taskId, value -> copy(
                value, "STEP_18_EXPORT_DOCUMENT", "WAITING_CONFIRMATION",
                value.inputJson(), value.outputJson(), null, value.timeoutAt(), false,
                value.version() + 1, null, null, null));
        return true;
    }

    @Override
    public synchronized boolean completeExport(
            UUID hospitalId, UUID taskId, String outputJson,
            UUID confirmedBy, Instant confirmedAt) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || !"STEP_18_EXPORT_DOCUMENT".equals(task.currentStep())
                || !"WAITING_CONFIRMATION".equals(task.status())) return false;
        saveStep(new StepData(
                UUID.randomUUID(), hospitalId, taskId,
                "STEP_18_EXPORT_DOCUMENT", 1, "COMPLETED",
                "document-export-confirmation/v1", "document-export/v1",
                task.outputJson(), outputJson, null, null,
                "[\"controlled-docx-renderer/v2\"]", null, null,
                confirmedAt, confirmedAt, true, confirmedBy, confirmedAt));
        tasks.put(taskId, copy(
                task, "STEP_18_EXPORT_DOCUMENT", "COMPLETED",
                task.inputJson(), outputJson, null, task.timeoutAt(), false,
                task.version() + 1, null, null, confirmedAt));
        return true;
    }

    private void completeConfirmationStep(UUID taskId, String stepCode,
                                          UUID confirmedBy, Instant confirmedAt) {
        completeConfirmationStep(taskId, stepCode, null, confirmedBy, confirmedAt);
    }

    private void completeConfirmationStep(UUID taskId, String stepCode, String outputJson,
                                          UUID confirmedBy, Instant confirmedAt) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            var step = steps.get(index);
            if (step.taskId().equals(taskId) && stepCode.equals(step.stepCode())
                    && "WAITING_CONFIRMATION".equals(step.status())) {
                steps.set(index, new StepData(step.id(), step.hospitalId(), step.taskId(),
                        step.stepCode(), step.attemptNo(), "COMPLETED", step.inputSchemaVersion(),
                        step.outputSchemaVersion(), step.inputJson(),
                        outputJson == null ? step.outputJson() : outputJson,
                        step.modelCallId(), step.promptVersion(), step.toolCallsJson(),
                        null, null, step.startedAt(), confirmedAt, true, confirmedBy, confirmedAt));
                break;
            }
        }
    }

    private void finishClarificationStep(UUID taskId, String stepCode,
                                         UUID confirmedBy, Instant confirmedAt) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            var step = steps.get(index);
            if (step.taskId().equals(taskId) && stepCode.equals(step.stepCode())
                    && "WAITING_CONFIRMATION".equals(step.status())) {
                String status = "STEP_03_ASK_CLARIFICATION".equals(stepCode)
                        ? "COMPLETED" : "SUPERSEDED";
                steps.set(index, new StepData(step.id(), step.hospitalId(), step.taskId(),
                        step.stepCode(), step.attemptNo(), status, step.inputSchemaVersion(),
                        step.outputSchemaVersion(), step.inputJson(), step.outputJson(),
                        step.modelCallId(), step.promptVersion(), step.toolCallsJson(),
                        null, null, step.startedAt(), confirmedAt, true, confirmedBy, confirmedAt));
                break;
            }
        }
    }

    @Override
    public synchronized void complete(UUID hospitalId, UUID taskId, String outputJson, Instant completedAt) {
        update(hospitalId, taskId, task -> copy(task, "STEP_06_BUILD_RESEARCH_QUESTION",
                "COMPLETED", task.inputJson(), outputJson, null, task.timeoutAt(),
                false, task.version() + 1, null, null, completedAt));
    }

    @Override
    public synchronized void fail(UUID hospitalId, UUID taskId, String code, String message) {
        update(hospitalId, taskId, task -> copy(task, task.currentStep(), "FAILED",
                task.inputJson(), task.outputJson(), null, task.timeoutAt(), false,
                task.version() + 1, code, message, task.completedAt()));
    }

    @Override
    public synchronized boolean cancel(UUID hospitalId, UUID taskId, Instant completedAt) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || List.of("COMPLETED", "CANCELLED").contains(task.status())) return false;
        tasks.put(taskId, copy(task, task.currentStep(), "CANCELLED", task.inputJson(),
                task.outputJson(), null, task.timeoutAt(), true, task.version() + 1,
                null, null, completedAt));
        ClaimHandle claim = activeClaims.remove(taskId);
        if (claim != null) attemptStatuses.put(claim.stepAttemptId(), "CANCELLED");
        return true;
    }

    @Override
    public synchronized boolean retry(UUID hospitalId, UUID taskId, Instant timeoutAt) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)
                || !"FAILED".equals(task.status())) return false;
        tasks.put(taskId, copy(task, task.currentStep(), "QUEUED", task.inputJson(),
                task.outputJson(), null, timeoutAt, false, task.version() + 1,
                null, null, null));
        return true;
    }

    @Override
    public synchronized void saveStep(StepData step) {
        steps.add(step);
    }

    @Override
    public synchronized EventData appendEvent(UUID hospitalId, UUID taskId, String eventType,
                                               String stepCode, String payloadJson, Instant occurredAt) {
        var event = new EventData(eventSequence.incrementAndGet(), hospitalId, taskId,
                eventType, stepCode, payloadJson, occurredAt);
        events.add(event);
        recordWorkspaceChange(hospitalId, taskId, occurredAt);
        return event;
    }

    private CommitOutcome completedOrStale(
            ClaimHandle claim, String completedStatus, List<PendingEvent> pendingEvents) {
        TaskData current = tasks.get(claim.taskId());
        if (!completedStatus.equals(attemptStatuses.get(claim.stepAttemptId()))) {
            return new CommitOutcome(CommitStatus.STALE_TOKEN, current, List.of());
        }
        List<EventData> committedEvents = pendingEvents.stream()
                .map(event -> eventsByStableKey.get(stableScope(
                        claim.hospitalId(), claim.taskId(), event.stableKey())))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingLong(EventData::id))
                .toList();
        return new CommitOutcome(
                CommitStatus.ALREADY_APPLIED, current, committedEvents);
    }

    private List<EventData> appendPendingEvents(
            UUID hospitalId, UUID taskId, List<PendingEvent> pendingEvents,
            Instant occurredAt) {
        List<EventData> committed = new ArrayList<>();
        for (PendingEvent pending : pendingEvents) {
            String scope = stableScope(hospitalId, taskId, pending.stableKey());
            EventData event = eventsByStableKey.get(scope);
            if (event == null) {
                event = new EventData(
                        eventSequence.incrementAndGet(), hospitalId, taskId,
                        pending.eventType(), pending.stepCode(),
                        pending.payloadJson(), occurredAt);
                eventsByStableKey.put(scope, event);
                events.add(event);
                recordWorkspaceChange(hospitalId, taskId, occurredAt);
            }
            committed.add(event);
        }
        committed.sort(Comparator.comparingLong(EventData::id));
        return List.copyOf(committed);
    }

    private String stableScope(UUID hospitalId, UUID taskId, String stableKey) {
        return hospitalId + "|" + taskId + "|" + stableKey;
    }

    private void recordWorkspaceChange(
            UUID hospitalId, UUID taskId, Instant occurredAt) {
        if (workspace == null) return;
        TaskData task = tasks.get(taskId);
        if (task != null && task.hospitalId().equals(hospitalId)) {
            workspace.recordMemoryChange(
                    hospitalId, task.projectId(), occurredAt);
        }
    }

    @Override
    public synchronized List<EventData> findEventsAfter(UUID hospitalId, UUID taskId, long afterEventId) {
        return events.stream().filter(event -> event.hospitalId().equals(hospitalId)
                        && event.taskId().equals(taskId) && event.id() > afterEventId)
                .sorted(Comparator.comparingLong(EventData::id)).toList();
    }

    private void update(UUID hospitalId, UUID taskId, java.util.function.UnaryOperator<TaskData> updater) {
        var task = tasks.get(taskId);
        if (task == null || !task.hospitalId().equals(hospitalId)) {
            throw new IllegalStateException("Agent任务不存在");
        }
        tasks.put(taskId, updater.apply(task));
    }

    private String truncate(String message) {
        if (message == null) return "未知错误";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private TaskData copy(TaskData source, String step, String status, String inputJson,
                          String outputJson, Instant leaseUntil, Instant timeoutAt,
                          boolean cancelRequested, long version, String errorCode,
                          String errorMessage, Instant completedAt) {
        return new TaskData(source.id(), source.hospitalId(), source.projectId(), source.createdBy(),
                step, status, inputJson, outputJson, leaseUntil, timeoutAt, cancelRequested,
                version, errorCode, errorMessage, source.createdAt(), Instant.now(), completedAt);
    }
}
