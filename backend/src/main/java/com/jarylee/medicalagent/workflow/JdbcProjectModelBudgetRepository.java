package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcProjectModelBudgetRepository
        implements ProjectModelBudgetRepository {
    private static final String COLUMNS = """
            id,hospital_id,project_id,currency,max_call_cost_micros,
            max_project_cost_micros,status,created_by,created_at,updated_at,
            version
            """;

    private final JdbcClient jdbc;

    public JdbcProjectModelBudgetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BudgetData lockOrCreate(
            UUID hospitalId, UUID projectId, UUID createdBy,
            String currency, long defaultMaxCallCostMicros,
            long defaultMaxProjectCostMicros, Instant now) {
        jdbc.sql("""
                insert into project_model_budget(
                    id,hospital_id,project_id,currency,max_call_cost_micros,
                    max_project_cost_micros,status,created_by,created_at,
                    updated_at,version
                ) values(
                    :id,:hospitalId,:projectId,:currency,:maxCall,
                    :maxProject,'ACTIVE',:createdBy,:now,:now,0
                )
                on conflict (hospital_id,project_id) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .param("currency", currency)
                .param("maxCall", defaultMaxCallCostMicros)
                .param("maxProject", defaultMaxProjectCostMicros)
                .param("createdBy", createdBy)
                .param("now", Timestamp.from(now))
                .update();
        return jdbc.sql("select " + COLUMNS + """
                        from project_model_budget
                        where hospital_id=:hospitalId
                          and project_id=:projectId
                        for update
                        """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query(this::map)
                .single();
    }

    @Override
    public Optional<BudgetData> find(
            UUID hospitalId, UUID projectId) {
        return jdbc.sql("select " + COLUMNS + """
                        from project_model_budget
                        where hospital_id=:hospitalId
                          and project_id=:projectId
                        """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query(this::map)
                .optional();
    }

    @Override
    public BudgetData update(
            UUID hospitalId, UUID projectId, long expectedVersion,
            long maxCallCostMicros, long maxProjectCostMicros,
            String status, Instant updatedAt) {
        int updated = jdbc.sql("""
                update project_model_budget
                set max_call_cost_micros=:maxCall,
                    max_project_cost_micros=:maxProject,
                    status=:status,updated_at=:updatedAt,
                    version=version+1
                where hospital_id=:hospitalId and project_id=:projectId
                  and version=:expectedVersion
                """)
                .param("maxCall", maxCallCostMicros)
                .param("maxProject", maxProjectCostMicros)
                .param("status", status)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated != 1) throw new IllegalStateException("模型预算版本冲突");
        return find(hospitalId, projectId).orElseThrow();
    }

    private BudgetData map(ResultSet result, int row) throws SQLException {
        return new BudgetData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getString("currency"),
                result.getLong("max_call_cost_micros"),
                result.getLong("max_project_cost_micros"),
                result.getString("status"),
                result.getObject("created_by", UUID.class),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(),
                result.getLong("version"));
    }
}
