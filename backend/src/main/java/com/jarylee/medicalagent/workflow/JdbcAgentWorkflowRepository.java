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
                        order by created_at desc
                        """)
                .param("hospitalId", hospitalId).param("projectId", projectId)
                .query(this::mapTask).list();
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
    public boolean cancel(UUID hospitalId, UUID taskId, Instant completedAt) {
        return jdbc.sql("""
                update ai_agent_task set status='CANCELLED',cancel_requested=true,lease_until=null,
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
                    timeout_at=:timeoutAt,completed_at=null,last_error_code=null,last_error_message=null,
                    version=version+1,updated_at=current_timestamp
                where hospital_id=:hospitalId and id=:taskId and status='FAILED'
                """).param("timeoutAt", Timestamp.from(timeoutAt))
                .param("hospitalId", hospitalId).param("taskId", taskId).update() == 1;
    }

    @Override
    public void saveStep(StepData step) {
        jdbc.sql("""
                insert into ai_agent_step_run(
                    id,hospital_id,task_id,step_code,attempt_no,status,input_schema_version,
                    output_schema_version,input_json,output_json,model_call_id,prompt_version,
                    tool_calls_json,error_code,error_message,
                    started_at,completed_at,requires_confirmation,confirmed_by,confirmed_at
                ) values(
                    :id,:hospitalId,:taskId,:stepCode,:attemptNo,:status,:inputSchemaVersion,
                    :outputSchemaVersion,cast(:inputJson as jsonb),cast(:outputJson as jsonb),
                    :modelCallId,:promptVersion,cast(:toolCallsJson as jsonb),
                    :errorCode,:errorMessage,:startedAt,:completedAt,:requiresConfirmation,
                    :confirmedBy,:confirmedAt
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
                .update();
    }

    @Override
    public EventData appendEvent(UUID hospitalId, UUID taskId, String eventType,
                                 String stepCode, String payloadJson, Instant occurredAt) {
        return jdbc.sql("""
                insert into ai_agent_event(
                    hospital_id,task_id,event_type,step_code,payload_json,occurred_at
                ) values(
                    :hospitalId,:taskId,:eventType,:stepCode,cast(:payloadJson as jsonb),:occurredAt
                ) returning id,hospital_id,task_id,event_type,step_code,payload_json::text,occurred_at
                """).param("hospitalId", hospitalId).param("taskId", taskId)
                .param("eventType", eventType).param("stepCode", stepCode)
                .param("payloadJson", payloadJson).param("occurredAt", Timestamp.from(occurredAt))
                .query(this::mapEvent).single();
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
