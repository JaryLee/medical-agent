package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcModelCallAuditRepository implements ModelCallAuditRepository {
    private static final String COLUMNS = """
            id,hospital_id,project_id,task_id,step_code,attempt_no,provider,model_name,
            prompt_version,input_schema_version,output_schema_version,input_sha256,
            output_sha256,input_snapshot_json::text,output_snapshot_json::text,
            raw_payload_object_key,payload_purged_at,status,error_code,error_message,started_at,completed_at,
            payload_retention_until,metadata_retention_until,
            logical_model_type,route_policy_version,route_reason,provider_request_id,
            usage_source,input_tokens,cached_input_tokens,output_tokens,total_tokens,
            price_version,price_currency,estimated_cost_micros,cost_status,
            reserved_cost_micros
            """;

    private final JdbcClient jdbc;

    public JdbcModelCallAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void start(ModelCallData call) {
        jdbc.sql("""
                insert into ai_model_call_log(
                    id,hospital_id,project_id,task_id,step_code,attempt_no,provider,
                    model_name,prompt_version,input_schema_version,output_schema_version,
                    input_sha256,input_snapshot_json,raw_payload_object_key,status,
                    started_at,payload_retention_until,metadata_retention_until,
                    logical_model_type,route_policy_version,route_reason,
                    usage_source,price_version,price_currency,cost_status,
                    reserved_cost_micros
                ) values(
                    :id,:hospitalId,:projectId,:taskId,:stepCode,:attemptNo,:provider,
                    :modelName,:promptVersion,:inputSchemaVersion,:outputSchemaVersion,
                    :inputSha256,cast(:inputSnapshotJson as jsonb),:rawPayloadObjectKey,
                    'REQUESTED',:startedAt,:payloadRetentionUntil,:metadataRetentionUntil,
                    :logicalModelType,:routePolicyVersion,:routeReason,
                    :usageSource,:priceVersion,:priceCurrency,:costStatus,
                    :reservedCostMicros
                )
                """)
                .param("id", call.id())
                .param("hospitalId", call.hospitalId())
                .param("projectId", call.projectId())
                .param("taskId", call.taskId())
                .param("stepCode", call.stepCode())
                .param("attemptNo", call.attemptNo())
                .param("provider", call.provider())
                .param("modelName", call.modelName())
                .param("promptVersion", call.promptVersion())
                .param("inputSchemaVersion", call.inputSchemaVersion())
                .param("outputSchemaVersion", call.outputSchemaVersion())
                .param("inputSha256", call.inputSha256())
                .param("inputSnapshotJson", call.inputSnapshotJson())
                .param("rawPayloadObjectKey", call.rawPayloadObjectKey())
                .param("startedAt", Timestamp.from(call.startedAt()))
                .param("payloadRetentionUntil", call.payloadRetentionUntil() == null
                        ? null : Timestamp.from(call.payloadRetentionUntil()))
                .param("metadataRetentionUntil",
                        Timestamp.from(call.metadataRetentionUntil()))
                .param("logicalModelType", call.logicalModelType())
                .param("routePolicyVersion", call.routePolicyVersion())
                .param("routeReason", call.routeReason())
                .param("usageSource", call.usageSource())
                .param("priceVersion", call.priceVersion())
                .param("priceCurrency", call.priceCurrency())
                .param("costStatus", call.costStatus())
                .param("reservedCostMicros", call.reservedCostMicros())
                .update();
    }

    @Override
    public void succeed(
            UUID callId, CompletionData completion, Instant completedAt) {
        int updated = jdbc.sql("""
                update ai_model_call_log
                set status='SUCCEEDED',output_sha256=:outputSha256,
                    output_snapshot_json=cast(:outputSnapshotJson as jsonb),
                    provider_request_id=:providerRequestId,
                    usage_source=:usageSource,
                    input_tokens=:inputTokens,
                    cached_input_tokens=:cachedInputTokens,
                    output_tokens=:outputTokens,
                    total_tokens=:totalTokens,
                    estimated_cost_micros=:estimatedCostMicros,
                    cost_status=:costStatus,
                    completed_at=:completedAt,error_code=null,error_message=null
                where id=:id and status='REQUESTED'
                """)
                .param("outputSha256", completion.outputSha256())
                .param("outputSnapshotJson", completion.outputSnapshotJson())
                .param("providerRequestId", completion.providerRequestId())
                .param("usageSource", completion.usageSource())
                .param("inputTokens", completion.inputTokens())
                .param("cachedInputTokens", completion.cachedInputTokens())
                .param("outputTokens", completion.outputTokens())
                .param("totalTokens", completion.totalTokens())
                .param("estimatedCostMicros", completion.estimatedCostMicros())
                .param("costStatus", completion.costStatus())
                .param("completedAt", Timestamp.from(completedAt))
                .param("id", callId)
                .update();
        requireUpdated(updated);
    }

    @Override
    public void fail(
            UUID callId, String errorCode, String errorMessage,
            Instant completedAt) {
        int updated = jdbc.sql("""
                update ai_model_call_log
                set status='FAILED',error_code=:errorCode,error_message=:errorMessage,
                    completed_at=:completedAt
                where id=:id and status='REQUESTED'
                """)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("completedAt", Timestamp.from(completedAt))
                .param("id", callId)
                .update();
        requireUpdated(updated);
    }

    @Override
    public List<ModelCallData> findByTask(UUID hospitalId, UUID taskId) {
        return jdbc.sql("select " + COLUMNS + """
                        from ai_model_call_log
                        where hospital_id=:hospitalId and task_id=:taskId
                        order by started_at,id
                        """)
                .param("hospitalId", hospitalId)
                .param("taskId", taskId)
                .query(this::map)
                .list();
    }

    @Override
    public List<ModelCallData> findByProject(
            UUID hospitalId, UUID projectId) {
        return jdbc.sql("select " + COLUMNS + """
                        from ai_model_call_log
                        where hospital_id=:hospitalId
                          and project_id=:projectId
                        order by started_at desc,id desc
                        """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query(this::map)
                .list();
    }

    @Override
    public ProjectConsumption projectConsumption(
            UUID hospitalId, UUID projectId) {
        return jdbc.sql("""
                        select
                          coalesce(sum(
                            case
                              when status='SUCCEEDED'
                                then coalesce(estimated_cost_micros,
                                              reserved_cost_micros,0)
                              when status in ('REQUESTED','FAILED')
                                then coalesce(reserved_cost_micros,0)
                              else 0
                            end
                          ),0) as committed_or_reserved,
                          coalesce(sum(
                            case when status='REQUESTED'
                              then coalesce(reserved_cost_micros,0)
                              else 0 end
                          ),0) as active_reserved,
                          coalesce(sum(
                            case when status='SUCCEEDED'
                              then coalesce(estimated_cost_micros,
                                            reserved_cost_micros,0)
                              else 0 end
                          ),0) as succeeded_cost,
                          count(*) as call_count
                        from ai_model_call_log
                        where hospital_id=:hospitalId
                          and project_id=:projectId
                        """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query((result, row) -> new ProjectConsumption(
                        result.getLong("committed_or_reserved"),
                        result.getLong("active_reserved"),
                        result.getLong("succeeded_cost"),
                        result.getLong("call_count")))
                .single();
    }

    @Override
    public int purgeExpiredPayloadSnapshots(Instant now, int limit) {
        return jdbc.sql("""
                with due as (
                    select id
                    from ai_model_call_log
                    where payload_retention_until <= :now
                      and payload_purged_at is null
                      and raw_payload_object_key is null
                      and status <> 'REQUESTED'
                    order by payload_retention_until,id
                    limit :limit
                    for update skip locked
                )
                update ai_model_call_log call
                set input_snapshot_json=null,output_snapshot_json=null,
                    payload_purged_at=:now
                from due
                where call.id=due.id
                """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .update();
    }

    @Override
    public int purgeExpiredMetadata(Instant now, int limit) {
        return jdbc.sql("""
                with due as materialized (
                    select id
                    from ai_model_call_log
                    where metadata_retention_until <= :now
                      and raw_payload_object_key is null
                      and status <> 'REQUESTED'
                    order by metadata_retention_until,id
                    limit :limit
                    for update skip locked
                ),
                detached as (
                    update ai_agent_step_run step
                    set model_call_id=null
                    from due
                    where step.model_call_id=due.id
                    returning step.id
                )
                delete from ai_model_call_log call
                using due
                where call.id=due.id
                """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .update();
    }

    @Override
    public long countExpiredObjectPayloads(Instant now) {
        return jdbc.sql("""
                select count(*)
                from ai_model_call_log
                where payload_retention_until <= :now
                  and payload_purged_at is null
                  and raw_payload_object_key is not null
                  and status <> 'REQUESTED'
                """)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    private ModelCallData map(ResultSet result, int row) throws SQLException {
        return new ModelCallData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("task_id", UUID.class),
                result.getString("step_code"),
                result.getInt("attempt_no"),
                result.getString("provider"),
                result.getString("model_name"),
                result.getString("prompt_version"),
                result.getString("input_schema_version"),
                result.getString("output_schema_version"),
                result.getString("input_sha256"),
                result.getString("output_sha256"),
                result.getString("input_snapshot_json"),
                result.getString("output_snapshot_json"),
                result.getString("raw_payload_object_key"),
                instant(result, "payload_purged_at"),
                result.getString("status"),
                result.getString("error_code"),
                result.getString("error_message"),
                result.getTimestamp("started_at").toInstant(),
                instant(result, "completed_at"),
                instant(result, "payload_retention_until"),
                result.getTimestamp("metadata_retention_until").toInstant(),
                result.getString("logical_model_type"),
                result.getString("route_policy_version"),
                result.getString("route_reason"),
                result.getString("provider_request_id"),
                result.getString("usage_source"),
                nullableLong(result, "input_tokens"),
                nullableLong(result, "cached_input_tokens"),
                nullableLong(result, "output_tokens"),
                nullableLong(result, "total_tokens"),
                result.getString("price_version"),
                result.getString("price_currency"),
                nullableLong(result, "estimated_cost_micros"),
                result.getString("cost_status"),
                nullableLong(result, "reserved_cost_micros"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("模型调用审计状态不可更新");
        }
    }
}
