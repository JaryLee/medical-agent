package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcAgentToolCallRepository implements AgentToolCallRepository {
    private static final String COLUMNS = """
            id,hospital_id,task_id,step_attempt_id,step_code,attempt_no,
            tool_call_key,operation_key,request_sha256,status,result_json::text,
            error_code,error_message,started_at,completed_at
            """;
    private final JdbcClient jdbc;

    public JdbcAgentToolCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public BeginOutcome begin(ToolCallData call) {
        Optional<ToolCallData> completed = findCompleted(
                call.hospitalId(), call.taskId(), call.stepCode(),
                call.operationKey());
        if (completed.isPresent()) return new BeginOutcome(false, completed.get());
        int inserted = jdbc.sql("""
                    insert into ai_agent_tool_call(
                        id,hospital_id,task_id,step_attempt_id,step_code,attempt_no,
                        tool_call_key,operation_key,request_sha256,status,started_at
                    ) values(
                        :id,:hospitalId,:taskId,:stepAttemptId,:stepCode,:attemptNo,
                        :toolCallKey,:operationKey,:requestSha256,'RUNNING',:startedAt
                    )
                    on conflict do nothing
                    """)
                    .param("id", call.id())
                    .param("hospitalId", call.hospitalId())
                    .param("taskId", call.taskId())
                    .param("stepAttemptId", call.stepAttemptId())
                    .param("stepCode", call.stepCode())
                    .param("attemptNo", call.attemptNo())
                    .param("toolCallKey", call.toolCallKey())
                    .param("operationKey", call.operationKey())
                    .param("requestSha256", call.requestSha256())
                .param("startedAt", Timestamp.from(call.startedAt()))
                .update();
        if (inserted == 1) {
            return new BeginOutcome(true, call);
        }
        ToolCallData existing = findActive(
                call.hospitalId(), call.taskId(), call.stepCode(),
                call.operationKey())
                .orElseGet(() -> findAttempt(call).orElseThrow());
        if ("COMPLETED".equals(existing.status())) {
            return new BeginOutcome(false, existing);
        }
        if ("RUNNING".equals(existing.status())
                && existing.operationKey().equals(call.operationKey())) {
            throw new IllegalStateException(
                    "相同Agent工具操作仍在执行，拒绝重复调用");
        }
        throw new IllegalStateException(
                "同一步骤尝试的工具调用已存在: " + existing.status());
    }

    @Override
    @Transactional
    public ToolCallData complete(
            UUID hospitalId, UUID taskId, UUID callId, String operationKey,
            String resultJson, Instant completedAt) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:operationKey))")
                .param("operationKey", operationKey)
                .query((result, row) -> 0).single();
        ToolCallData current = findById(hospitalId, taskId, callId).orElseThrow();
        Optional<ToolCallData> completed = findCompleted(
                hospitalId, taskId, current.stepCode(), operationKey);
        if (completed.isPresent() && !completed.get().id().equals(callId)) {
            jdbc.sql("""
                    update ai_agent_tool_call
                    set status='SUPERSEDED',completed_at=:completedAt
                    where hospital_id=:hospitalId and task_id=:taskId
                      and id=:callId and status='RUNNING'
                    """)
                    .param("completedAt", Timestamp.from(completedAt))
                    .param("hospitalId", hospitalId)
                    .param("taskId", taskId)
                    .param("callId", callId)
                    .update();
            return completed.get();
        }
        int updated = jdbc.sql("""
                update ai_agent_tool_call
                set status='COMPLETED',result_json=cast(:resultJson as jsonb),
                    completed_at=:completedAt,error_code=null,error_message=null
                where hospital_id=:hospitalId and task_id=:taskId
                  and id=:callId and status='RUNNING'
                """)
                .param("resultJson", resultJson)
                .param("completedAt", Timestamp.from(completedAt))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("callId", callId)
                .update();
        if (updated != 1) throw new IllegalStateException("Agent工具调用当前不可完成");
        return findById(hospitalId, taskId, callId).orElseThrow();
    }

    @Override
    public void fail(
            UUID hospitalId, UUID taskId, UUID callId,
            String errorCode, String errorMessage, Instant completedAt) {
        jdbc.sql("""
                update ai_agent_tool_call
                set status='FAILED',error_code=:errorCode,error_message=:errorMessage,
                    completed_at=:completedAt
                where hospital_id=:hospitalId and task_id=:taskId
                  and id=:callId and status='RUNNING'
                """)
                .param("errorCode", errorCode)
                .param("errorMessage", truncate(errorMessage))
                .param("completedAt", Timestamp.from(completedAt))
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("callId", callId)
                .update();
    }

    private Optional<ToolCallData> findCompleted(
            UUID hospitalId, UUID taskId, String stepCode, String operationKey) {
        return jdbc.sql("select " + COLUMNS + """
                        from ai_agent_tool_call
                        where hospital_id=:hospitalId and task_id=:taskId
                          and step_code=:stepCode and operation_key=:operationKey
                          and status='COMPLETED'
                        """)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("stepCode", stepCode)
                .param("operationKey", operationKey)
                .query(this::map).optional();
    }

    private Optional<ToolCallData> findActive(
            UUID hospitalId, UUID taskId, String stepCode, String operationKey) {
        return jdbc.sql("select " + COLUMNS + """
                        from ai_agent_tool_call
                        where hospital_id=:hospitalId and task_id=:taskId
                          and step_code=:stepCode and operation_key=:operationKey
                          and status in ('RUNNING','COMPLETED')
                        """)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("stepCode", stepCode)
                .param("operationKey", operationKey)
                .query(this::map).optional();
    }

    private Optional<ToolCallData> findAttempt(ToolCallData call) {
        return jdbc.sql("select " + COLUMNS + """
                        from ai_agent_tool_call
                        where hospital_id=:hospitalId and task_id=:taskId
                          and step_code=:stepCode and attempt_no=:attemptNo
                          and tool_call_key=:toolCallKey
                        """)
                .param("hospitalId", call.hospitalId())
                .param("taskId", call.taskId())
                .param("stepCode", call.stepCode())
                .param("attemptNo", call.attemptNo())
                .param("toolCallKey", call.toolCallKey())
                .query(this::map).optional();
    }

    private Optional<ToolCallData> findById(
            UUID hospitalId, UUID taskId, UUID callId) {
        return jdbc.sql("select " + COLUMNS + """
                        from ai_agent_tool_call
                        where hospital_id=:hospitalId and task_id=:taskId and id=:callId
                        """)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .param("callId", callId)
                .query(this::map).optional();
    }

    private ToolCallData map(ResultSet result, int row) throws SQLException {
        Timestamp completed = result.getTimestamp(15);
        return new ToolCallData(
                result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                result.getObject(3, UUID.class), result.getObject(4, UUID.class),
                result.getString(5), result.getInt(6), result.getString(7),
                result.getString(8), result.getString(9), result.getString(10),
                result.getString(11), result.getString(12), result.getString(13),
                result.getTimestamp(14).toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
