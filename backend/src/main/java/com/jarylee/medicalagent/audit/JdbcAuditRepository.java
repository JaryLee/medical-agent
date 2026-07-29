package com.jarylee.medicalagent.audit;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.sql.Timestamp;

@Repository
@Profile("postgres")
public class JdbcAuditRepository implements AuditRepository {
    private final JdbcClient jdbc;

    public JdbcAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(AuditData audit) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", audit.id());
        params.put("hospitalId", audit.hospitalId());
        params.put("actorId", audit.actorId());
        params.put("action", audit.action());
        params.put("resourceType", audit.resourceType());
        params.put("resourceId", audit.resourceId());
        params.put("occurredAt", Timestamp.from(audit.occurredAt()));
        jdbc.sql("""
                insert into operation_audit(
                    id,hospital_id,actor_user_id,action,resource_type,resource_id,occurred_at
                ) values(:id,:hospitalId,:actorId,:action,:resourceType,:resourceId,:occurredAt)
                """).params(params).update();
    }

    @Override
    public List<AuditData> findRecent(java.util.UUID hospitalId, int limit) {
        String scope = hospitalId == null ? "" : " where hospital_id=:hospitalId";
        var query = jdbc.sql("""
                select id,hospital_id,actor_user_id,action,resource_type,resource_id,occurred_at
                from operation_audit
                """ + scope + " order by occurred_at desc limit :limit").param("limit", limit);
        if (hospitalId != null) query = query.param("hospitalId", hospitalId);
        return query.query((rs, row) -> new AuditData(
                rs.getObject("id", java.util.UUID.class),
                rs.getObject("hospital_id", java.util.UUID.class),
                rs.getObject("actor_user_id", java.util.UUID.class),
                rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
                rs.getTimestamp("occurred_at").toInstant())).list();
    }
}
