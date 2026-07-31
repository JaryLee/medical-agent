package com.jarylee.medicalagent.research;

import com.jarylee.medicalagent.common.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@Profile("postgres")
public class JdbcProjectRepository implements ProjectRepository {
    private final JdbcClient jdbc;

    public JdbcProjectRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<ProjectData> findById(UUID hospitalId, UUID id) {
        return jdbc.sql("""
                select id,hospital_id,project_key,project_code,project_name,version
                from research_project where hospital_id=:hospitalId and id=:id
                """).param("hospitalId", hospitalId).param("id", id)
                .query(this::map).optional();
    }

    @Override
    public Optional<ProjectData> findByKey(UUID hospitalId, String projectKey) {
        return jdbc.sql("""
                select id,hospital_id,project_key,project_code,project_name,version
                from research_project
                where hospital_id=:hospitalId and project_key=:projectKey
                """).param("hospitalId", hospitalId).param("projectKey", projectKey)
                .query(this::map).optional();
    }

    @Override
    public List<ProjectData> findAll(UUID hospitalId) {
        return jdbc.sql("""
                select id,hospital_id,project_key,project_code,project_name,version
                from research_project where hospital_id=:hospitalId order by project_code
                """).param("hospitalId", hospitalId).query(this::map).list();
    }

    @Override
    public Optional<ProjectData> findVisibleByKey(
            UUID hospitalId, String projectKey, UUID userId, boolean hospitalAdmin) {
        return jdbc.sql("""
                select project.id,project.hospital_id,project.project_key,
                       project.project_code,project.project_name,project.version
                from research_project project
                where project.hospital_id=:hospitalId
                  and project.project_key=:projectKey
                  and (
                    :hospitalAdmin
                    or exists (
                      select 1
                      from project_member member
                      where member.hospital_id=project.hospital_id
                        and member.project_id=project.id
                        and member.user_id=:userId
                    )
                  )
                """)
                .param("hospitalId", hospitalId)
                .param("projectKey", projectKey)
                .param("hospitalAdmin", hospitalAdmin)
                .param("userId", userId)
                .query(this::map)
                .optional();
    }

    @Override
    public List<ProjectData> findVisible(
            UUID hospitalId, UUID userId, boolean hospitalAdmin) {
        return jdbc.sql("""
                select project.id,project.hospital_id,project.project_key,
                       project.project_code,project.project_name,project.version
                from research_project project
                where project.hospital_id=:hospitalId
                  and (
                    :hospitalAdmin
                    or exists (
                      select 1
                      from project_member member
                      where member.hospital_id=project.hospital_id
                        and member.project_id=project.id
                        and member.user_id=:userId
                    )
                  )
                order by project.project_code,project.id
                """)
                .param("hospitalId", hospitalId)
                .param("hospitalAdmin", hospitalAdmin)
                .param("userId", userId)
                .query(this::map)
                .list();
    }

    @Override
    public Optional<UUID> findIdempotentResource(UUID hospitalId, UUID userId, String operation, String key) {
        return jdbc.sql("""
                select resource_id from idempotency_record
                where hospital_id=:hospitalId and user_id=:userId
                  and operation=:operation and idempotency_key=:key
                """).params(Map.of("hospitalId", hospitalId, "userId", userId,
                        "operation", operation, "key", key))
                .query(String.class).optional().map(UUID::fromString);
    }

    @Override
    public ProjectData insert(UUID hospitalId, String projectKey, String code, String name) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into research_project(
                        id,hospital_id,project_key,project_code,project_name,version
                    ) values(:id,:hospitalId,:projectKey,:code,:name,0)
                    """).params(Map.of(
                            "id", id,
                            "hospitalId", hospitalId,
                            "projectKey", projectKey,
                            "code", code,
                            "name", name))
                    .update();
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("本院课题编码已存在");
        }
        return new ProjectData(id, hospitalId, projectKey, code, name, 0);
    }

    @Override
    public void saveIdempotency(UUID hospitalId, UUID userId, String operation, String key, UUID resourceId) {
        jdbc.sql("""
                insert into idempotency_record(id,hospital_id,user_id,idempotency_key,operation,resource_id)
                values(:id,:hospitalId,:userId,:key,:operation,:resourceId)
                """).params(Map.of("id", UUID.randomUUID(), "hospitalId", hospitalId, "userId", userId,
                        "key", key, "operation", operation, "resourceId", resourceId.toString())).update();
    }

    @Override
    public ProjectData update(UUID hospitalId, UUID id, String name, long expectedVersion) {
        int changed = jdbc.sql("""
                update research_project set project_name=:name,version=version+1
                where hospital_id=:hospitalId and id=:id and version=:version
                """).params(Map.of("name", name, "hospitalId", hospitalId, "id", id,
                        "version", expectedVersion)).update();
        if (changed == 0) {
            if (findById(hospitalId, id).isEmpty()) {
                throw BusinessException.projectNotFound();
            }
            throw BusinessException.conflict("课题已被其他用户修改");
        }
        return findById(hospitalId, id).orElseThrow();
    }

    private ProjectData map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ProjectData(rs.getObject("id", UUID.class), rs.getObject("hospital_id", UUID.class),
                rs.getString("project_key"), rs.getString("project_code"),
                rs.getString("project_name"), rs.getLong("version"));
    }
}
