package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcAgentWorkflowRepository implements AgentWorkflowRepository {
    private static final String TASK_COLUMNS = """
            id,hospital_id,project_id,created_by,current_step,status,input_json::text,
            output_json::text,lease_until,timeout_at,cancel_requested,version,
            last_error_code,last_error_message,created_at,updated_at,completed_at
            """;
    private final JdbcClient jdbc;

    public JdbcAgentWorkflowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TaskData> findById(UUID hospitalId, UUID taskId) {
        return jdbc.sql("select " + TASK_COLUMNS + """
                        from ai_agent_task where hospital_id=:hospitalId and id=:taskId
                        """)
                .param("hospitalId", hospitalId).param("taskId", taskId)
                .query(this::mapTask).optional();
    }

    @Override
    public List<TaskData> findByProject(UUID hospitalId, UUID projectId) {
        return jdbc.sql("select " + TASK_COLUMNS + """
                        from ai_agent_task
                        where hospital_id=:hospitalId and project_id=:projectId
                        order by created_at desc, id desc
                        """)
                .param("hospitalId", hospitalId).param("projectId", projectId)
                .query(this::mapTask).list();
    }

    @Override
    public Optional<StepData> findLatestStep(
            UUID hospitalId, UUID taskId, String stepCode) {
        return jdbc.sql("""
                select id,hospital_id,task_id,step_code,attempt_no,status,
                       input_schema_version,output_schema_version,
                       input_json::text,output_json::text,model_call_id,
                       prompt_version,tool_calls_json::text,error_code,error_message,
                       started_at,completed_at,requires_confirmation,
                       confirmed_by,confirmed_at
                from ai_agent_step_run
                where hospital_id=:hospitalId
                  and task_id=:taskId
                  and step_code=:stepCode
                order by attempt_no desc,started_at desc,id desc
                limit 1
                """)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("stepCode", stepCode)
                .query((result, row) -> new StepData(
                        result.getObject("id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getObject("task_id", UUID.class),
                        result.getString("step_code"),
                        result.getInt("attempt_no"),
                        result.getString("status"),
                        result.getString("input_schema_version"),
                        result.getString("output_schema_version"),
                        result.getString("input_json"),
                        result.getString("output_json"),
                        result.getObject("model_call_id", UUID.class),
                        result.getString("prompt_version"),
                        result.getString("tool_calls_json"),
                        result.getString("error_code"),
                        result.getString("error_message"),
                        result.getTimestamp("started_at").toInstant(),
                        result.getTimestamp("completed_at") == null
                                ? null : result.getTimestamp("completed_at").toInstant(),
                        result.getBoolean("requires_confirmation"),
                        result.getObject("confirmed_by", UUID.class),
                        result.getTimestamp("confirmed_at") == null
                                ? null : result.getTimestamp("confirmed_at").toInstant()))
                .optional();
    }

    @Override
    public Optional<TaskData> findByIdempotency(UUID hospitalId, UUID userId, String key) {
        return jdbc.sql("select " + TASK_COLUMNS + """
                        from ai_agent_task
                        where hospital_id=:hospitalId and created_by=:userId and idempotency_key=:key
                        """)
                .param("hospitalId", hospitalId).param("userId", userId).param("key", key)
                .query(this::mapTask).optional();
    }

    @Override
    public TaskData create(TaskData task, String key) {
        try {
            jdbc.sql("""
                    insert into ai_agent_task(
                        id,hospital_id,project_id,created_by,idempotency_key,current_step,status,
                        input_json,output_json,lease_until,timeout_at,cancel_requested,version,
                        created_at,updated_at
                    ) values(
                        :id,:hospitalId,:projectId,:createdBy,:key,:currentStep,:status,
                        cast(:inputJson as jsonb),cast(:outputJson as jsonb),:leaseUntil,:timeoutAt,
                        :cancelRequested,:version,:createdAt,:updatedAt
                    )
                    """)
                    .param("id", task.id()).param("hospitalId", task.hospitalId())
                    .param("projectId", task.projectId()).param("createdBy", task.createdBy())
                    .param("key", key).param("currentStep", task.currentStep())
                    .param("status", task.status()).param("inputJson", task.inputJson())
                    .param("outputJson", task.outputJson()).param("leaseUntil", timestamp(task.leaseUntil()))
                    .param("timeoutAt", timestamp(task.timeoutAt()))
                    .param("cancelRequested", task.cancelRequested()).param("version", task.version())
                    .param("createdAt", Timestamp.from(task.createdAt()))
                    .param("updatedAt", Timestamp.from(task.updatedAt())).update();
            return task;
        } catch (DuplicateKeyException exception) {
            return findByIdempotency(task.hospitalId(), task.createdBy(), key).orElseThrow(() -> exception);
        }
    }

    @Override
    public List<TaskData> findRunnable(Instant now, int limit) {
        return jdbc.sql("select " + TASK_COLUMNS + """
                        from ai_agent_task
                        where cancel_requested=false and timeout_at>:now
                          and (status='QUEUED' or (status='RUNNING' and lease_until<:now))
                        order by created_at limit :limit
                        """)
                .param("now", Timestamp.from(now)).param("limit", limit)
                .query(this::mapTask).list();
    }

    @Override
    public List<TaskData> findTimedOut(Instant now, int limit) {
        return jdbc.sql("select " + TASK_COLUMNS + """
                        from ai_agent_task
                        where cancel_requested=false and timeout_at<=:now
                          and status in ('QUEUED','RUNNING')
                        order by created_at limit :limit
                        """)
                .param("now", Timestamp.from(now)).param("limit", limit)
                .query(this::mapTask).list();
    }

    @Override
    @Transactional
    public Optional<ClaimedTask> claimNext(
            Instant now, String leaseOwner, Duration leaseDuration) {
        UUID executionToken = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant leaseUntil = now.plus(leaseDuration);
        return jdbc.sql("""
                with candidate as (
                    select id,hospital_id,current_step,current_step_attempt_id
                    from ai_agent_task
                    where cancel_requested=false and timeout_at>:now
                      and (
                        status='QUEUED'
                        or (status='RUNNING' and lease_until<:now)
                      )
                    order by created_at,id
                    for update skip locked
                    limit 1
                ),
                attempt_number as (
                    select candidate.*,
                           coalesce(max(existing.attempt_no),0)+1 as attempt_no
                    from candidate
                    left join ai_agent_step_attempt existing
                      on existing.hospital_id=candidate.hospital_id
                     and existing.task_id=candidate.id
                     and existing.step_code=candidate.current_step
                    group by candidate.id,candidate.hospital_id,candidate.current_step,
                             candidate.current_step_attempt_id
                ),
                previous_attempt as (
                    update ai_agent_step_attempt previous
                    set status='LEASE_LOST',completed_at=:now,
                        error_code='LEASE_EXPIRED',
                        error_message='执行租约已过期，由新 Worker 接管'
                    from attempt_number
                    where previous.id=attempt_number.current_step_attempt_id
                      and previous.status='RUNNING'
                    returning previous.id
                ),
                inserted_attempt as (
                    insert into ai_agent_step_attempt(
                        id,hospital_id,task_id,step_code,attempt_no,execution_token,
                        lease_owner,status,started_at,heartbeat_at
                    )
                    select :attemptId,hospital_id,id,current_step,attempt_no,
                           :executionToken,:leaseOwner,'RUNNING',:now,:now
                    from attempt_number
                    cross join (select count(*) from previous_attempt) fenced
                    returning id,task_id,step_code,attempt_no
                ),
                claimed as (
                    update ai_agent_task task
                    set status='RUNNING',lease_until=:leaseUntil,
                        execution_token=:executionToken,lease_owner=:leaseOwner,
                        lease_acquired_at=:now,heartbeat_at=:now,
                        current_step_attempt_id=:attemptId,
                        version=task.version+1,updated_at=:now
                    from inserted_attempt
                    where task.id=inserted_attempt.task_id
                    returning task.*
                ),
                started_event as (
                    insert into ai_agent_event(
                        hospital_id,task_id,event_key,event_type,step_code,
                        payload_json,occurred_at
                    )
                    select claimed.hospital_id,claimed.id,:startedEventKey,
                           'TASK_STARTED',claimed.current_step,
                           '{"status":"RUNNING"}'::jsonb,:now
                    from claimed
                    returning id,hospital_id,task_id,event_type,step_code,
                              payload_json::text,occurred_at
                )
                select
                       claimed.id,claimed.hospital_id,claimed.project_id,
                       claimed.created_by,claimed.current_step,claimed.status,
                       claimed.input_json::text,claimed.output_json::text,
                       claimed.lease_until,claimed.timeout_at,
                       claimed.cancel_requested,claimed.version,
                       claimed.last_error_code,claimed.last_error_message,
                       claimed.created_at,claimed.updated_at,claimed.completed_at,
                       inserted_attempt.id as claimed_attempt_id,
                       inserted_attempt.attempt_no as claimed_attempt_no,
                       started_event.id as started_event_id,
                       started_event.hospital_id as started_event_hospital_id,
                       started_event.task_id as started_event_task_id,
                       started_event.event_type as started_event_type,
                       started_event.step_code as started_event_step_code,
                       started_event.payload_json as started_event_payload_json,
                       started_event.occurred_at as started_event_occurred_at
                from claimed
                join inserted_attempt on inserted_attempt.task_id=claimed.id
                join started_event on started_event.task_id=claimed.id
                """)
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("leaseOwner", leaseOwner)
                .param("executionToken", executionToken)
                .param("attemptId", attemptId)
                .param("startedEventKey", attemptId + ":started")
                .query((result, row) -> {
                    TaskData task = mapTask(result, row);
                    ClaimHandle handle = new ClaimHandle(
                            task.hospitalId(), task.id(), task.currentStep(),
                            executionToken,
                            result.getObject("claimed_attempt_id", UUID.class),
                            result.getInt("claimed_attempt_no"),
                            leaseOwner);
                    EventData startedEvent = new EventData(
                            result.getLong("started_event_id"),
                            result.getObject("started_event_hospital_id", UUID.class),
                            result.getObject("started_event_task_id", UUID.class),
                            result.getString("started_event_type"),
                            result.getString("started_event_step_code"),
                            result.getString("started_event_payload_json"),
                            result.getTimestamp("started_event_occurred_at").toInstant());
                    return new ClaimedTask(task, handle, List.of(startedEvent));
                })
                .optional();
    }

    @Override
    @Transactional
    public boolean heartbeat(
            ClaimHandle claim, Instant leaseUntil, Instant heartbeatAt) {
        int taskUpdated = jdbc.sql("""
                update ai_agent_task
                set lease_until=:leaseUntil,heartbeat_at=:heartbeatAt,
                    updated_at=:heartbeatAt
                where hospital_id=:hospitalId and id=:taskId
                  and status='RUNNING' and execution_token=:executionToken
                  and current_step_attempt_id=:attemptId
                """)
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("heartbeatAt", Timestamp.from(heartbeatAt))
                .param("hospitalId", claim.hospitalId())
                .param("taskId", claim.taskId())
                .param("executionToken", claim.executionToken())
                .param("attemptId", claim.stepAttemptId())
                .update();
        if (taskUpdated != 1) return false;
        int attemptUpdated = jdbc.sql("""
                update ai_agent_step_attempt
                set heartbeat_at=:heartbeatAt
                where id=:attemptId and execution_token=:executionToken
                  and status='RUNNING'
                """)
                .param("heartbeatAt", Timestamp.from(heartbeatAt))
                .param("attemptId", claim.stepAttemptId())
                .param("executionToken", claim.executionToken())
                .update();
        if (attemptUpdated != 1) {
            throw new IllegalStateException(
                    "Agent任务与步骤尝试心跳不一致");
        }
        return true;
    }

    @Override
    @Transactional
    public CommitOutcome commitClaim(
            ClaimHandle claim, List<StepData> steps, TaskTransition transition,
            List<PendingEvent> events, Instant committedAt) {
        Optional<TaskData> locked = lockActiveClaim(claim);
        if (locked.isEmpty()) {
            return completedOrStale(claim, "COMPLETED", events);
        }
        for (StepData step : steps) {
            insertStep(step, claim.stepAttemptId());
        }
        int attemptUpdated = jdbc.sql("""
                update ai_agent_step_attempt
                set status='COMPLETED',completed_at=:committedAt,
                    heartbeat_at=:committedAt,error_code=null,error_message=null
                where id=:attemptId and execution_token=:executionToken
                  and status='RUNNING'
                """)
                .param("committedAt", Timestamp.from(committedAt))
                .param("attemptId", claim.stepAttemptId())
                .param("executionToken", claim.executionToken())
                .update();
        if (attemptUpdated != 1) {
            throw new IllegalStateException("Agent步骤尝试当前不可完成");
        }
        int taskUpdated = jdbc.sql("""
                update ai_agent_task
                set current_step=:nextStep,status=:nextStatus,
                    output_json=cast(:outputJson as jsonb),
                    lease_until=null,execution_token=null,lease_owner=null,
                    lease_acquired_at=null,heartbeat_at=null,
                    current_step_attempt_id=null,
                    completed_at=:completedAt,last_error_code=null,last_error_message=null,
                    version=version+1,updated_at=:committedAt
                where hospital_id=:hospitalId and id=:taskId
                  and status='RUNNING' and execution_token=:executionToken
                  and current_step_attempt_id=:attemptId
                  and current_step=:stepCode and cancel_requested=false
                """)
                .param("nextStep", transition.nextStep())
                .param("nextStatus", transition.nextStatus())
                .param("outputJson", transition.outputJson())
                .param("completedAt", timestamp(transition.completedAt()))
                .param("committedAt", Timestamp.from(committedAt))
                .param("hospitalId", claim.hospitalId())
                .param("taskId", claim.taskId())
                .param("executionToken", claim.executionToken())
                .param("attemptId", claim.stepAttemptId())
                .param("stepCode", claim.stepCode())
                .update();
        if (taskUpdated != 1) {
            throw new IllegalStateException("Agent任务执行令牌在事务中失效");
        }
        List<EventData> committedEvents = insertPendingEvents(
                claim.hospitalId(), claim.taskId(), events, committedAt);
        TaskData current = findById(claim.hospitalId(), claim.taskId()).orElseThrow();
        return new CommitOutcome(CommitStatus.APPLIED, current, committedEvents);
    }

    @Override
    @Transactional
    public CommitOutcome failClaim(
            ClaimHandle claim, String errorCode, String errorMessage,
            PendingEvent event, Instant committedAt) {
        Optional<TaskData> locked = lockActiveClaim(claim);
        if (locked.isEmpty()) {
            return completedOrStale(claim, "FAILED", List.of(event));
        }
        String safeMessage = truncate(errorMessage);
        int attemptUpdated = jdbc.sql("""
                update ai_agent_step_attempt
                set status='FAILED',completed_at=:committedAt,
                    heartbeat_at=:committedAt,error_code=:errorCode,
                    error_message=:errorMessage
                where id=:attemptId and execution_token=:executionToken
                  and status='RUNNING'
                """)
                .param("committedAt", Timestamp.from(committedAt))
                .param("errorCode", errorCode)
                .param("errorMessage", safeMessage)
                .param("attemptId", claim.stepAttemptId())
                .param("executionToken", claim.executionToken())
                .update();
        if (attemptUpdated != 1) {
            throw new IllegalStateException("Agent步骤尝试当前不可失败");
        }
        int taskUpdated = jdbc.sql("""
                update ai_agent_task
                set status='FAILED',lease_until=null,execution_token=null,
                    lease_owner=null,lease_acquired_at=null,heartbeat_at=null,
                    current_step_attempt_id=null,last_error_code=:errorCode,
                    last_error_message=:errorMessage,version=version+1,
                    updated_at=:committedAt
                where hospital_id=:hospitalId and id=:taskId
                  and status='RUNNING' and execution_token=:executionToken
                  and current_step_attempt_id=:attemptId
                  and current_step=:stepCode and cancel_requested=false
                """)
                .param("errorCode", errorCode)
                .param("errorMessage", safeMessage)
                .param("committedAt", Timestamp.from(committedAt))
                .param("hospitalId", claim.hospitalId())
                .param("taskId", claim.taskId())
                .param("executionToken", claim.executionToken())
                .param("attemptId", claim.stepAttemptId())
                .param("stepCode", claim.stepCode())
                .update();
        if (taskUpdated != 1) {
            throw new IllegalStateException("Agent任务执行令牌在失败事务中失效");
        }
        List<EventData> committedEvents = insertPendingEvents(
                claim.hospitalId(), claim.taskId(), List.of(event), committedAt);
        TaskData current = findById(claim.hospitalId(), claim.taskId()).orElseThrow();
        return new CommitOutcome(CommitStatus.APPLIED, current, committedEvents);
    }

    @Override
    @Transactional
    public CommitOutcome failTimedOut(
            UUID hospitalId, UUID taskId, long expectedVersion, Instant now,
            PendingEvent event) {
        Optional<TaskData> locked = jdbc.sql("select " + TASK_COLUMNS + """
                        from ai_agent_task
                        where hospital_id=:hospitalId and id=:taskId
                          and version=:expectedVersion
                          and cancel_requested=false and timeout_at<=:now
                          and status in ('QUEUED','RUNNING')
                        for update
                        """)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("expectedVersion", expectedVersion)
                .param("now", Timestamp.from(now))
                .query(this::mapTask).optional();
        if (locked.isEmpty()) {
            return new CommitOutcome(
                    CommitStatus.STALE_TOKEN,
                    findById(hospitalId, taskId).orElse(null), List.of());
        }
        jdbc.sql("""
                update ai_agent_step_attempt
                set status='FAILED',completed_at=:now,error_code='TASK_TIMEOUT',
                    error_message='Agent任务执行超时'
                where id=(
                    select current_step_attempt_id from ai_agent_task
                    where hospital_id=:hospitalId and id=:taskId
                      and status not in ('COMPLETED','CANCELLED')
                ) and status='RUNNING'
                """)
                .param("now", Timestamp.from(now))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        jdbc.sql("""
                update ai_agent_task
                set status='FAILED',lease_until=null,execution_token=null,
                    lease_owner=null,lease_acquired_at=null,heartbeat_at=null,
                    current_step_attempt_id=null,last_error_code='TASK_TIMEOUT',
                    last_error_message='Agent任务执行超时',version=version+1,
                    updated_at=:now
                where hospital_id=:hospitalId and id=:taskId
                  and version=:expectedVersion
                """)
                .param("now", Timestamp.from(now))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("expectedVersion", expectedVersion)
                .update();
        List<EventData> committedEvents = insertPendingEvents(
                hospitalId, taskId, List.of(event), now);
        TaskData current = findById(hospitalId, taskId).orElseThrow();
        return new CommitOutcome(CommitStatus.APPLIED, current, committedEvents);
    }

    @Override
    public boolean claim(UUID hospitalId, UUID taskId, long version, Instant leaseUntil) {
        return jdbc.sql("""
                update ai_agent_task set status='RUNNING',lease_until=:leaseUntil,
                    version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId and version=:version
                  and cancel_requested=false
                  and (status='QUEUED' or status='RUNNING')
                """).param("leaseUntil", Timestamp.from(leaseUntil))
                .param("hospitalId", hospitalId).param("taskId", taskId)
                .param("version", version).update() == 1;
    }

    @Override
    public void waitForClarification(UUID hospitalId, UUID taskId, String outputJson) {
        jdbc.sql("""
                update ai_agent_task set current_step='STEP_03_ASK_CLARIFICATION',
                    status='WAITING_CONFIRMATION',output_json=cast(:outputJson as jsonb),
                    lease_until=null,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId and cancel_requested=false
                """).param("outputJson", outputJson).param("hospitalId", hospitalId)
                .param("taskId", taskId).update();
    }

    @Override
    @Transactional
    public Optional<ClarificationRoundData> confirmClarifications(
            UUID hospitalId, UUID taskId, String sourceStep, String inputJson,
            String questionsJson, String answersJson, UUID confirmedBy,
            Instant confirmedAt, Instant timeoutAt) {
        int updated = jdbc.sql("""
                update ai_agent_task set current_step='STEP_04_GENERATE_RESEARCH_DIRECTIONS',
                    status='QUEUED',input_json=cast(:inputJson as jsonb),lease_until=null,
                    timeout_at=:timeoutAt,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step=:sourceStep
                  and current_step in ('STEP_03_ASK_CLARIFICATION','STEP_05_CONFIRM_DIRECTION')
                  and status='WAITING_CONFIRMATION' and cancel_requested=false
                """).param("inputJson", inputJson).param("timeoutAt", Timestamp.from(timeoutAt))
                .param("hospitalId", hospitalId).param("taskId", taskId)
                .param("sourceStep", sourceStep).update();
        if (updated != 1) return Optional.empty();

        finishClarificationStep(
                hospitalId, taskId, sourceStep, confirmedBy, confirmedAt);
        int roundNo = jdbc.sql("""
                select coalesce(max(round_no), 0) + 1
                from ai_agent_clarification_round
                where hospital_id=:hospitalId and task_id=:taskId
                """).param("hospitalId", hospitalId).param("taskId", taskId)
                .query(Integer.class).single();
        ClarificationRoundData round = new ClarificationRoundData(
                UUID.randomUUID(), hospitalId, taskId, roundNo, sourceStep,
                questionsJson, answersJson, confirmedBy, confirmedAt);
        jdbc.sql("""
                insert into ai_agent_clarification_round(
                    id,hospital_id,task_id,round_no,source_step,questions_json,
                    answers_json,submitted_by,submitted_at
                ) values(
                    :id,:hospitalId,:taskId,:roundNo,:sourceStep,cast(:questionsJson as jsonb),
                    cast(:answersJson as jsonb),:submittedBy,:submittedAt
                )
                """).param("id", round.id()).param("hospitalId", hospitalId)
                .param("taskId", taskId).param("roundNo", roundNo)
                .param("sourceStep", sourceStep).param("questionsJson", questionsJson)
                .param("answersJson", answersJson).param("submittedBy", confirmedBy)
                .param("submittedAt", Timestamp.from(confirmedAt)).update();
        return Optional.of(round);
    }

    @Override
    public List<ClarificationRoundData> findClarificationRounds(
            UUID hospitalId, UUID taskId) {
        return jdbc.sql("""
                select id,hospital_id,task_id,round_no,source_step,
                    questions_json::text,answers_json::text,submitted_by,submitted_at
                from ai_agent_clarification_round
                where hospital_id=:hospitalId and task_id=:taskId
                order by round_no
                """).param("hospitalId", hospitalId).param("taskId", taskId)
                .query((result, row) -> new ClarificationRoundData(
                        result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                        result.getObject(3, UUID.class), result.getInt(4), result.getString(5),
                        result.getString(6), result.getString(7),
                        result.getObject(8, UUID.class), result.getTimestamp(9).toInstant()))
                .list();
    }

    @Override
    public void waitForConfirmation(UUID hospitalId, UUID taskId, String outputJson) {
        jdbc.sql("""
                update ai_agent_task set current_step='STEP_05_CONFIRM_DIRECTION',
                    status='WAITING_CONFIRMATION',output_json=cast(:outputJson as jsonb),
                    lease_until=null,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId and cancel_requested=false
                """).param("outputJson", outputJson).param("hospitalId", hospitalId)
                .param("taskId", taskId).update();
    }

    @Override
    public boolean confirm(UUID hospitalId, UUID taskId, String inputJson,
                           UUID confirmedBy, Instant confirmedAt, Instant timeoutAt) {
        int updated = jdbc.sql("""
                update ai_agent_task set current_step='STEP_05_CONFIRM_DIRECTION',
                    status='QUEUED',input_json=cast(:inputJson as jsonb),lease_until=null,
                    timeout_at=:timeoutAt,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_05_CONFIRM_DIRECTION'
                  and status='WAITING_CONFIRMATION' and cancel_requested=false
                """).param("inputJson", inputJson).param("timeoutAt", Timestamp.from(timeoutAt))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId).update();
        if (updated == 1) {
            completeConfirmationStep(hospitalId, taskId, "STEP_05_CONFIRM_DIRECTION",
                    confirmedBy, confirmedAt);
        }
        return updated == 1;
    }

    @Override
    public void waitForSearchStrategyConfirmation(
            UUID hospitalId, UUID taskId, String outputJson) {
        jdbc.sql("""
                update ai_agent_task set current_step='STEP_07_BUILD_SEARCH_STRATEGY',
                    status='WAITING_CONFIRMATION',output_json=cast(:outputJson as jsonb),
                    lease_until=null,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId and cancel_requested=false
                """).param("outputJson", outputJson).param("hospitalId", hospitalId)
                .param("taskId", taskId).update();
    }

    @Override
    @Transactional
    public boolean confirmSearchStrategy(
            UUID hospitalId, UUID taskId, String taskOutputJson, String strategyOutputJson,
            UUID confirmedBy, Instant confirmedAt, Instant timeoutAt) {
        int updated = jdbc.sql("""
                update ai_agent_task set current_step='STEP_08_SEARCH_PUBMED',
                    status='QUEUED',output_json=cast(:taskOutputJson as jsonb),
                    lease_until=null,timeout_at=:timeoutAt,completed_at=null,version=version+1,
                    updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_07_BUILD_SEARCH_STRATEGY'
                  and status='WAITING_CONFIRMATION' and cancel_requested=false
                """).param("taskOutputJson", taskOutputJson)
                .param("timeoutAt", Timestamp.from(timeoutAt))
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
        if (updated == 1) {
            completeSearchStrategyStep(
                    hospitalId, taskId, strategyOutputJson, confirmedBy, confirmedAt);
        }
        return updated == 1;
    }

    @Override
    public void queueClinicalTrialsSearch(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task set current_step='STEP_09_SEARCH_CLINICAL_TRIALS',
                    status='QUEUED',output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=null,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_08_SEARCH_PUBMED'
                  and status='RUNNING' and cancel_requested=false
                """).param("outputJson", outputJson)
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
        if (updated != 1) throw new IllegalStateException("PubMed检索步骤当前不可完成");
    }

    @Override
    public void queueLiteratureValidation(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task set current_step='STEP_10_VALIDATE_LITERATURE',
                    status='QUEUED',output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=null,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_09_SEARCH_CLINICAL_TRIALS'
                  and status='RUNNING' and cancel_requested=false
                """).param("outputJson", outputJson)
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
        if (updated != 1) throw new IllegalStateException("临床试验检索步骤当前不可完成");
    }

    @Override
    public void queueSimilarResearchAnalysis(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task set current_step='STEP_11_ANALYZE_SIMILAR_RESEARCH',
                    status='QUEUED',output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=null,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_10_VALIDATE_LITERATURE'
                  and status='RUNNING' and cancel_requested=false
                """).param("outputJson", outputJson)
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
        if (updated != 1) throw new IllegalStateException("文献验证步骤当前不可完成");
    }

    @Override
    public void queueObservationalDesignRecommendation(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task
                set current_step='STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN',
                    status='QUEUED',output_json=cast(:outputJson as jsonb),
                    lease_until=null,completed_at=null,version=version+1,
                    updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_11_ANALYZE_SIMILAR_RESEARCH'
                  and status='RUNNING' and cancel_requested=false
                """).param("outputJson", outputJson)
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
        if (updated != 1) throw new IllegalStateException("相似研究分析步骤当前不可完成");
    }

    @Override
    public void waitForObservationalDesignConfirmation(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task set status='WAITING_CONFIRMATION',
                    output_json=cast(:outputJson as jsonb),lease_until=null,
                    version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN'
                  and status='RUNNING' and cancel_requested=false
                """).param("outputJson", outputJson)
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
        if (updated != 1) {
            throw new IllegalStateException("观察性研究设计推荐当前不可等待确认");
        }
    }

    @Override
    @Transactional
    public boolean confirmObservationalDesign(
            UUID hospitalId, UUID taskId, String taskOutputJson,
            String recommendationOutputJson, UUID confirmedBy, Instant confirmedAt,
            Instant timeoutAt) {
        int updated = jdbc.sql("""
                update ai_agent_task
                set current_step='STEP_13_GENERATE_PROTOCOL_SECTIONS',
                    status='QUEUED',
                    output_json=cast(:taskOutputJson as jsonb),lease_until=null,
                    timeout_at=:timeoutAt,completed_at=null,version=version+1,
                    updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN'
                  and status='WAITING_CONFIRMATION' and cancel_requested=false
                """)
                .param("taskOutputJson", taskOutputJson)
                .param("timeoutAt", Timestamp.from(timeoutAt))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        if (updated != 1) return false;
        int stepUpdated = jdbc.sql("""
                update ai_agent_step_run set status='COMPLETED',
                    output_json=cast(:outputJson as jsonb),completed_at=:confirmedAt,
                    confirmed_by=:confirmedBy,confirmed_at=:confirmedAt
                where hospital_id=:hospitalId and task_id=:taskId
                  and step_code='STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN'
                  and status='WAITING_CONFIRMATION'
                """)
                .param("outputJson", recommendationOutputJson)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("confirmedBy", confirmedBy)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        if (stepUpdated != 1) {
            throw new IllegalStateException("观察性研究设计确认步骤记录不存在");
        }
        return true;
    }

    @Override
    public void queueStatisticalDraft(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task
                set current_step='STEP_14_GENERATE_STATISTICAL_DRAFT',
                    status='QUEUED',
                    output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=null,version=version+1,
                    updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_13_GENERATE_PROTOCOL_SECTIONS'
                  and status='RUNNING' and cancel_requested=false
                """)
                .param("outputJson", outputJson)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("研究方案章节生成步骤当前不可完成");
        }
    }

    @Override
    public void queueClaimCitationValidation(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task
                set current_step='STEP_15_VALIDATE_CLAIMS_AND_CITATIONS',
                    status='QUEUED',
                    output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=null,version=version+1,
                    updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_14_GENERATE_STATISTICAL_DRAFT'
                  and status='RUNNING' and cancel_requested=false
                """)
                .param("outputJson", outputJson)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("统计分析草案步骤当前不可完成");
        }
    }

    @Override
    public void queueStrobeCompletenessCheck(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task
                set current_step='STEP_16_CHECK_STROBE_COMPLETENESS',
                    status='QUEUED',
                    output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=null,version=version+1,
                    updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_15_VALIDATE_CLAIMS_AND_CITATIONS'
                  and status='RUNNING' and cancel_requested=false
                """)
                .param("outputJson", outputJson)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("主张与引用验证步骤当前不可完成");
        }
    }

    @Override
    public void waitForExpertReview(
            UUID hospitalId, UUID taskId, String outputJson) {
        int updated = jdbc.sql("""
                update ai_agent_task
                set current_step='STEP_17_WAIT_EXPERT_REVIEW',
                    status='WAITING_CONFIRMATION',
                    output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=null,version=version+1,
                    updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_16_CHECK_STROBE_COMPLETENESS'
                  and status='RUNNING' and cancel_requested=false
                """)
                .param("outputJson", outputJson)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("STROBE 完整性预检查步骤当前不可完成");
        }
    }

    @Override
    public boolean markExpertReviewReturned(UUID hospitalId, UUID taskId) {
        return jdbc.sql("""
                update ai_agent_task
                set status='REVISION_REQUIRED',lease_until=null,
                    version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_17_WAIT_EXPERT_REVIEW'
                  and status='WAITING_CONFIRMATION'
                """)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update() == 1;
    }

    @Override
    public boolean updateProtocolRevisionOutput(
            UUID hospitalId,
            UUID taskId,
            long expectedVersion,
            String outputJson,
            Instant updatedAt) {
        return jdbc.sql("""
                update ai_agent_task
                set output_json=cast(:outputJson as jsonb),
                    version=version+1,
                    updated_at=:updatedAt
                where hospital_id=:hospitalId
                  and id=:taskId
                  and current_step='STEP_17_WAIT_EXPERT_REVIEW'
                  and status='REVISION_REQUIRED'
                  and version=:expectedVersion
                """)
                .param("outputJson", outputJson)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    @Override
    @Transactional
    public boolean advanceToExport(
            UUID hospitalId, UUID taskId, UUID confirmedBy, Instant confirmedAt) {
        int updated = jdbc.sql("""
                update ai_agent_task
                set current_step='STEP_18_EXPORT_DOCUMENT',
                    status='WAITING_CONFIRMATION',lease_until=null,
                    version=version+1,updated_at=:confirmedAt
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_17_WAIT_EXPERT_REVIEW'
                  and status='WAITING_CONFIRMATION'
                """)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        if (updated != 1) return false;
        jdbc.sql("""
                update ai_agent_step_run
                set status='COMPLETED',completed_at=:confirmedAt,
                    confirmed_by=:confirmedBy,confirmed_at=:confirmedAt,
                    version=version+1
                where hospital_id=:hospitalId and task_id=:taskId
                  and step_code='STEP_17_WAIT_EXPERT_REVIEW'
                  and status='WAITING_CONFIRMATION'
                """)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("confirmedBy", confirmedBy)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        return true;
    }

    @Override
    @Transactional
    public boolean completeExport(
            UUID hospitalId, UUID taskId, String outputJson,
            UUID confirmedBy, Instant confirmedAt) {
        int updated = jdbc.sql("""
                update ai_agent_task
                set current_step='STEP_18_EXPORT_DOCUMENT',status='COMPLETED',
                    output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=:confirmedAt,version=version+1,
                    updated_at=:confirmedAt
                where hospital_id=:hospitalId and id=:taskId
                  and current_step='STEP_18_EXPORT_DOCUMENT'
                  and status='WAITING_CONFIRMATION'
                  and cancel_requested=false
                """)
                .param("outputJson", outputJson)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .update();
        if (updated != 1) return false;
        jdbc.sql("""
                insert into ai_agent_step_run(
                    id,hospital_id,task_id,step_code,attempt_no,status,
                    input_schema_version,output_schema_version,input_json,
                    output_json,model_call_id,prompt_version,tool_calls_json,
                    error_code,error_message,started_at,completed_at,
                    requires_confirmation,confirmed_by,confirmed_at,version
                ) values(
                    :id,:hospitalId,:taskId,'STEP_18_EXPORT_DOCUMENT',1,'COMPLETED',
                    'document-export-confirmation/v1','document-export/v1',
                    cast(:inputJson as jsonb),cast(:outputJson as jsonb),null,null,
                    cast(:toolCallsJson as jsonb),null,null,:confirmedAt,:confirmedAt,
                    true,:confirmedBy,:confirmedAt,0
                )
                """)
                .param("id", UUID.randomUUID())
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("inputJson", outputJson)
                .param("outputJson", outputJson)
                .param("toolCallsJson", "[\"controlled-docx-renderer/v2\"]")
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("confirmedBy", confirmedBy)
                .update();
        return true;
    }

    @Override
    public void complete(UUID hospitalId, UUID taskId, String outputJson, Instant completedAt) {
        jdbc.sql("""
                update ai_agent_task set current_step='STEP_06_BUILD_RESEARCH_QUESTION',
                    status='COMPLETED',output_json=cast(:outputJson as jsonb),lease_until=null,
                    completed_at=:completedAt,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId and cancel_requested=false
                """).param("outputJson", outputJson).param("completedAt", Timestamp.from(completedAt))
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
    }

    @Override
    public void fail(UUID hospitalId, UUID taskId, String code, String message) {
        jdbc.sql("""
                update ai_agent_task set status='FAILED',lease_until=null,last_error_code=:code,
                    last_error_message=:message,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId and cancel_requested=false
                """).param("code", code).param("message", truncate(message))
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
    }

    @Override
    @Transactional
    public boolean cancel(UUID hospitalId, UUID taskId, Instant completedAt) {
        jdbc.sql("""
                update ai_agent_step_attempt
                set status='CANCELLED',completed_at=:completedAt,
                    error_code='TASK_CANCELLED',error_message='任务已取消'
                where id=(
                    select current_step_attempt_id from ai_agent_task
                    where hospital_id=:hospitalId and id=:taskId
                ) and status='RUNNING'
                """)
                .param("completedAt", Timestamp.from(completedAt))
                .param("hospitalId", hospitalId).param("taskId", taskId)
                .update();
        return jdbc.sql("""
                update ai_agent_task set status='CANCELLED',cancel_requested=true,lease_until=null,
                    execution_token=null,lease_owner=null,lease_acquired_at=null,
                    heartbeat_at=null,current_step_attempt_id=null,
                    completed_at=:completedAt,version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId
                  and status not in ('COMPLETED','CANCELLED')
                """).param("completedAt", Timestamp.from(completedAt))
                .param("hospitalId", hospitalId).param("taskId", taskId).update() == 1;
    }

    @Override
    public boolean retry(UUID hospitalId, UUID taskId, Instant timeoutAt) {
        return jdbc.sql("""
                update ai_agent_task set status='QUEUED',cancel_requested=false,lease_until=null,
                    execution_token=null,lease_owner=null,lease_acquired_at=null,
                    heartbeat_at=null,current_step_attempt_id=null,
                    timeout_at=:timeoutAt,completed_at=null,last_error_code=null,last_error_message=null,
                    version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId and status='FAILED'
                """).param("timeoutAt", Timestamp.from(timeoutAt))
                .param("hospitalId", hospitalId).param("taskId", taskId).update() == 1;
    }

    @Override
    public void saveStep(StepData step) {
        insertStep(step, null);
    }

    private void insertStep(StepData step, UUID stepAttemptId) {
        jdbc.sql("""
                insert into ai_agent_step_run(
                    id,hospital_id,task_id,step_code,attempt_no,status,input_schema_version,
                    output_schema_version,input_json,output_json,model_call_id,prompt_version,
                    tool_calls_json,error_code,error_message,
                    started_at,completed_at,requires_confirmation,confirmed_by,confirmed_at,
                    step_attempt_id
                ) values(
                    :id,:hospitalId,:taskId,:stepCode,:attemptNo,:status,:inputSchemaVersion,
                    :outputSchemaVersion,cast(:inputJson as jsonb),cast(:outputJson as jsonb),
                    :modelCallId,:promptVersion,cast(:toolCallsJson as jsonb),
                    :errorCode,:errorMessage,:startedAt,:completedAt,:requiresConfirmation,
                    :confirmedBy,:confirmedAt,:stepAttemptId
                )
                """).param("id", step.id()).param("hospitalId", step.hospitalId())
                .param("taskId", step.taskId()).param("stepCode", step.stepCode())
                .param("attemptNo", step.attemptNo()).param("status", step.status())
                .param("inputSchemaVersion", step.inputSchemaVersion())
                .param("outputSchemaVersion", step.outputSchemaVersion())
                .param("inputJson", step.inputJson()).param("outputJson", step.outputJson())
                .param("modelCallId", step.modelCallId()).param("promptVersion", step.promptVersion())
                .param("toolCallsJson", step.toolCallsJson())
                .param("errorCode", step.errorCode()).param("errorMessage", step.errorMessage())
                .param("startedAt", Timestamp.from(step.startedAt()))
                .param("completedAt", timestamp(step.completedAt()))
                .param("requiresConfirmation", step.requiresConfirmation())
                .param("confirmedBy", step.confirmedBy()).param("confirmedAt", timestamp(step.confirmedAt()))
                .param("stepAttemptId", stepAttemptId)
                .update();
    }

    @Override
    public EventData appendEvent(UUID hospitalId, UUID taskId, String eventType,
                                 String stepCode, String payloadJson, Instant occurredAt) {
        return jdbc.sql("""
                insert into ai_agent_event(
                    hospital_id,task_id,event_key,event_type,step_code,payload_json,occurred_at
                ) values(
                    :hospitalId,:taskId,:eventKey,:eventType,:stepCode,
                    cast(:payloadJson as jsonb),:occurredAt
                ) returning id,hospital_id,task_id,event_type,step_code,payload_json::text,occurred_at
                """).param("hospitalId", hospitalId).param("taskId", taskId)
                .param("eventKey", "direct:" + UUID.randomUUID())
                .param("eventType", eventType).param("stepCode", stepCode)
                .param("payloadJson", payloadJson).param("occurredAt", Timestamp.from(occurredAt))
                .query(this::mapEvent).single();
    }

    private Optional<TaskData> lockActiveClaim(ClaimHandle claim) {
        return jdbc.sql("select " + TASK_COLUMNS + """
                        from ai_agent_task
                        where hospital_id=:hospitalId and id=:taskId
                          and status='RUNNING' and cancel_requested=false
                          and current_step=:stepCode
                          and execution_token=:executionToken
                          and current_step_attempt_id=:attemptId
                        for update
                        """)
                .param("hospitalId", claim.hospitalId())
                .param("taskId", claim.taskId())
                .param("stepCode", claim.stepCode())
                .param("executionToken", claim.executionToken())
                .param("attemptId", claim.stepAttemptId())
                .query(this::mapTask).optional();
    }

    private CommitOutcome completedOrStale(
            ClaimHandle claim, String completedStatus, List<PendingEvent> pendingEvents) {
        Optional<String> attemptStatus = jdbc.sql("""
                        select status from ai_agent_step_attempt
                        where id=:attemptId and execution_token=:executionToken
                        """)
                .param("attemptId", claim.stepAttemptId())
                .param("executionToken", claim.executionToken())
                .query(String.class).optional();
        TaskData current = findById(claim.hospitalId(), claim.taskId()).orElse(null);
        if (attemptStatus.filter(completedStatus::equals).isEmpty()) {
            return new CommitOutcome(CommitStatus.STALE_TOKEN, current, List.of());
        }
        return new CommitOutcome(
                CommitStatus.ALREADY_APPLIED, current,
                findEventsByKeys(claim.hospitalId(), claim.taskId(), pendingEvents));
    }

    private List<EventData> insertPendingEvents(
            UUID hospitalId, UUID taskId, List<PendingEvent> pendingEvents,
            Instant occurredAt) {
        for (PendingEvent event : pendingEvents) {
            jdbc.sql("""
                    insert into ai_agent_event(
                        hospital_id,task_id,event_key,event_type,step_code,
                        payload_json,occurred_at
                    ) values(
                        :hospitalId,:taskId,:eventKey,:eventType,:stepCode,
                        cast(:payloadJson as jsonb),:occurredAt
                    )
                    on conflict (hospital_id,task_id,event_key) do nothing
                    """)
                    .param("hospitalId", hospitalId)
                    .param("taskId", taskId)
                    .param("eventKey", event.stableKey())
                    .param("eventType", event.eventType())
                    .param("stepCode", event.stepCode())
                    .param("payloadJson", event.payloadJson())
                    .param("occurredAt", Timestamp.from(occurredAt))
                    .update();
        }
        return findEventsByKeys(hospitalId, taskId, pendingEvents);
    }

    private List<EventData> findEventsByKeys(
            UUID hospitalId, UUID taskId, List<PendingEvent> pendingEvents) {
        List<EventData> result = new ArrayList<>();
        for (PendingEvent event : pendingEvents) {
            jdbc.sql("""
                    select id,hospital_id,task_id,event_type,step_code,
                           payload_json::text,occurred_at
                    from ai_agent_event
                    where hospital_id=:hospitalId and task_id=:taskId
                      and event_key=:eventKey
                    """)
                    .param("hospitalId", hospitalId)
                    .param("taskId", taskId)
                    .param("eventKey", event.stableKey())
                    .query(this::mapEvent).optional()
                    .ifPresent(result::add);
        }
        result.sort(java.util.Comparator.comparingLong(EventData::id));
        return List.copyOf(result);
    }

    @Override
    public List<EventData> findEventsAfter(UUID hospitalId, UUID taskId, long afterEventId) {
        return jdbc.sql("""
                select id,hospital_id,task_id,event_type,step_code,payload_json::text,occurred_at
                from ai_agent_event
                where hospital_id=:hospitalId and task_id=:taskId and id>:afterEventId
                order by id
                """).param("hospitalId", hospitalId).param("taskId", taskId)
                .param("afterEventId", afterEventId).query(this::mapEvent).list();
    }

    private TaskData mapTask(ResultSet result, int row) throws SQLException {
        return new TaskData(result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                result.getObject(3, UUID.class), result.getObject(4, UUID.class),
                result.getString(5), result.getString(6), result.getString(7),
                result.getString(8), instant(result, 9), instant(result, 10),
                result.getBoolean(11), result.getLong(12), result.getString(13),
                result.getString(14), instant(result, 15), instant(result, 16), instant(result, 17));
    }

    private EventData mapEvent(ResultSet result, int row) throws SQLException {
        return new EventData(result.getLong(1), result.getObject(2, UUID.class),
                result.getObject(3, UUID.class), result.getString(4), result.getString(5),
                result.getString(6), result.getTimestamp(7).toInstant());
    }

    private Instant instant(ResultSet result, int column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String truncate(String message) {
        if (message == null) return "未知错误";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private void completeConfirmationStep(UUID hospitalId, UUID taskId, String stepCode,
                                          UUID confirmedBy, Instant confirmedAt) {
        jdbc.sql("""
                update ai_agent_step_run set confirmed_by=:confirmedBy,confirmed_at=:confirmedAt,
                    status='COMPLETED',completed_at=:confirmedAt,version=version+1
                where hospital_id=:hospitalId and task_id=:taskId
                  and step_code=:stepCode and status='WAITING_CONFIRMATION'
                """).param("confirmedBy", confirmedBy)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("hospitalId", hospitalId).param("taskId", taskId)
                .param("stepCode", stepCode).update();
    }

    private void completeSearchStrategyStep(
            UUID hospitalId, UUID taskId, String outputJson,
            UUID confirmedBy, Instant confirmedAt) {
        jdbc.sql("""
                update ai_agent_step_run set output_json=cast(:outputJson as jsonb),
                    confirmed_by=:confirmedBy,confirmed_at=:confirmedAt,
                    status='COMPLETED',completed_at=:confirmedAt,version=version+1
                where hospital_id=:hospitalId and task_id=:taskId
                  and step_code='STEP_07_BUILD_SEARCH_STRATEGY'
                  and status='WAITING_CONFIRMATION'
                """).param("outputJson", outputJson).param("confirmedBy", confirmedBy)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("hospitalId", hospitalId).param("taskId", taskId).update();
    }

    private void finishClarificationStep(UUID hospitalId, UUID taskId, String stepCode,
                                         UUID confirmedBy, Instant confirmedAt) {
        String status = "STEP_03_ASK_CLARIFICATION".equals(stepCode)
                ? "COMPLETED" : "SUPERSEDED";
        jdbc.sql("""
                update ai_agent_step_run set confirmed_by=:confirmedBy,confirmed_at=:confirmedAt,
                    status=:status,completed_at=:confirmedAt,version=version+1
                where hospital_id=:hospitalId and task_id=:taskId
                  and step_code=:stepCode and status='WAITING_CONFIRMATION'
                """).param("confirmedBy", confirmedBy)
                .param("confirmedAt", Timestamp.from(confirmedAt)).param("status", status)
                .param("hospitalId", hospitalId).param("taskId", taskId)
                .param("stepCode", stepCode).update();
    }
}
