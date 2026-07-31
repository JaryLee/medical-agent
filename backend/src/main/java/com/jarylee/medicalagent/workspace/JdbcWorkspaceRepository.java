package com.jarylee.medicalagent.workspace;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcWorkspaceRepository implements WorkspaceRepository {
    private final JdbcClient jdbc;

    public JdbcWorkspaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Cursor requireCursor(UUID hospitalId, UUID projectId, Instant now) {
        Optional<Cursor> existing = findCursor(hospitalId, projectId);
        if (existing.isPresent()) return existing.get();
        jdbc.sql("select bump_project_workspace_cursor(:hospitalId,:projectId,:now)")
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .param("now", Timestamp.from(now))
                .query()
                .singleValue();
        return findCursor(hospitalId, projectId).orElseThrow();
    }

    @Override
    public List<ProjectEventData> findEventsAfter(
            UUID hospitalId, UUID projectId, long afterEventId, int limit) {
        return jdbc.sql("""
                select id,hospital_id,project_id,read_model_version,
                       event_type,occurred_at
                from project_read_model_event
                where hospital_id=:hospitalId
                  and project_id=:projectId
                  and id>:afterEventId
                order by id
                limit :limit
                """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .param("afterEventId", afterEventId)
                .param("limit", limit)
                .query(this::mapEvent)
                .list();
    }

    @Override
    public Optional<Long> earliestEventId(UUID hospitalId, UUID projectId) {
        return jdbc.sql("""
                select min(id)
                from project_read_model_event
                where hospital_id=:hospitalId and project_id=:projectId
                """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query(Long.class)
                .optional();
    }

    @Override
    @Transactional
    public Reservation reserve(CommandDraft command) {
        int inserted = jdbc.sql("""
                with locked_cursor as materialized (
                    select read_model_version
                    from project_workspace_cursor
                    where hospital_id=:hospitalId
                      and project_id=:projectId
                    for update
                )
                insert into project_workspace_command(
                    id,hospital_id,project_id,actor_user_id,action_code,
                    idempotency_key,request_sha256,expected_read_model_version,
                    status,created_at
                )
                select
                    :id,:hospitalId,:projectId,:actorUserId,:actionCode,
                    :idempotencyKey,:requestSha256,:expectedVersion,
                    'RUNNING',:createdAt
                from locked_cursor
                where read_model_version=:expectedVersion
                on conflict (
                    hospital_id,project_id,actor_user_id,idempotency_key
                ) do nothing
                """)
                .param("id", command.id())
                .param("hospitalId", command.hospitalId())
                .param("projectId", command.projectId())
                .param("actorUserId", command.actorUserId())
                .param("actionCode", command.actionCode())
                .param("idempotencyKey", command.idempotencyKey())
                .param("requestSha256", command.requestSha256())
                .param("expectedVersion", command.expectedReadModelVersion())
                .param("createdAt", Timestamp.from(command.createdAt()))
                .update();
        if (inserted == 1) return Reservation.newReservation();
        Optional<CommandData> existing = findCommand(
                command.hospitalId(), command.projectId(), command.actorUserId(),
                command.idempotencyKey());
        return existing.map(Reservation::existingReservation)
                .orElseGet(Reservation::versionConflictReservation);
    }

    @Override
    public void completeCommand(
            UUID hospitalId,
            UUID commandId,
            long resultReadModelVersion,
            String responseJson,
            Instant completedAt) {
        int updated = jdbc.sql("""
                update project_workspace_command
                set status='COMPLETED',
                    result_read_model_version=:resultVersion,
                    response_json=cast(:responseJson as jsonb),
                    completed_at=:completedAt
                where hospital_id=:hospitalId
                  and id=:commandId
                  and status='RUNNING'
                """)
                .param("resultVersion", resultReadModelVersion)
                .param("responseJson", responseJson)
                .param("completedAt", Timestamp.from(completedAt))
                .param("hospitalId", hospitalId)
                .param("commandId", commandId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("V2 课题动作幂等记录无法完成");
        }
    }

    @Override
    public void abortCommand(UUID hospitalId, UUID commandId) {
        jdbc.sql("""
                delete from project_workspace_command
                where hospital_id=:hospitalId
                  and id=:commandId
                  and status='RUNNING'
                """)
                .param("hospitalId", hospitalId)
                .param("commandId", commandId)
                .update();
    }

    @Override
    public Optional<CommandData> findCommand(
            UUID hospitalId,
            UUID projectId,
            UUID actorUserId,
            String idempotencyKey) {
        return jdbc.sql("""
                select id,hospital_id,project_id,actor_user_id,action_code,
                       idempotency_key,request_sha256,
                       expected_read_model_version,result_read_model_version,
                       status,response_json::text,created_at,completed_at
                from project_workspace_command
                where hospital_id=:hospitalId
                  and project_id=:projectId
                  and actor_user_id=:actorUserId
                  and idempotency_key=:idempotencyKey
                """)
                .params(Map.of(
                        "hospitalId", hospitalId,
                        "projectId", projectId,
                        "actorUserId", actorUserId,
                        "idempotencyKey", idempotencyKey))
                .query(this::mapCommand)
                .optional();
    }

    @Override
    public void recordMemoryChange(UUID hospitalId, UUID projectId, Instant occurredAt) {
        // PostgreSQL changes are recorded by V27 database triggers.
    }

    private Optional<Cursor> findCursor(UUID hospitalId, UUID projectId) {
        return jdbc.sql("""
                select project_id,hospital_id,read_model_version,
                       coalesce(latest_event_id,0) latest_event_id,updated_at
                from project_workspace_cursor
                where hospital_id=:hospitalId and project_id=:projectId
                """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query((result, row) -> new Cursor(
                        result.getObject("project_id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getLong("read_model_version"),
                        result.getLong("latest_event_id"),
                        result.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    private ProjectEventData mapEvent(ResultSet result, int row) throws SQLException {
        return new ProjectEventData(
                result.getLong("id"),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getLong("read_model_version"),
                result.getString("event_type"),
                result.getTimestamp("occurred_at").toInstant());
    }

    private CommandData mapCommand(ResultSet result, int row) throws SQLException {
        long resultVersion = result.getLong("result_read_model_version");
        boolean resultVersionNull = result.wasNull();
        return new CommandData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("actor_user_id", UUID.class),
                result.getString("action_code"),
                result.getString("idempotency_key"),
                result.getString("request_sha256"),
                result.getLong("expected_read_model_version"),
                resultVersionNull ? null : resultVersion,
                result.getString("status"),
                result.getString("response_json"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("completed_at") == null
                        ? null : result.getTimestamp("completed_at").toInstant());
    }
}
