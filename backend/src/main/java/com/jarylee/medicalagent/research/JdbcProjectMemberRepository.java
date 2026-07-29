package com.jarylee.medicalagent.research;

import com.jarylee.medicalagent.common.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcProjectMemberRepository implements ProjectMemberRepository {
    private final JdbcClient jdbc;

    public JdbcProjectMemberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(UUID hospitalId, UUID projectId, UUID userId, ProjectMemberRole role) {
        try {
            jdbc.sql("""
                    insert into project_member(hospital_id,project_id,user_id,member_role)
                    values(:hospitalId,:projectId,:userId,:role)
                    """).params(Map.of("hospitalId", hospitalId, "projectId", projectId,
                            "userId", userId, "role", role.name())).update();
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("用户已是课题成员");
        }
    }

    @Override
    public List<MemberData> findAll(UUID hospitalId, UUID projectId) {
        return jdbc.sql("""
                select project_id,user_id,member_role from project_member
                where hospital_id=:hospitalId and project_id=:projectId order by created_at
                """).params(Map.of("hospitalId", hospitalId, "projectId", projectId))
                .query((rs, row) -> new MemberData(
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        ProjectMemberRole.valueOf(rs.getString("member_role"))))
                .list();
    }

    @Override
    public Optional<ProjectMemberRole> findRole(UUID hospitalId, UUID projectId, UUID userId) {
        return jdbc.sql("""
                select member_role from project_member
                where hospital_id=:hospitalId and project_id=:projectId and user_id=:userId
                """).params(Map.of("hospitalId", hospitalId, "projectId", projectId, "userId", userId))
                .query(String.class).optional().map(ProjectMemberRole::valueOf);
    }
}
