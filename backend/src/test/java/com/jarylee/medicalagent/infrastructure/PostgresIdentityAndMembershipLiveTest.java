package com.jarylee.medicalagent.infrastructure;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.JdbcAuditRepository;
import com.jarylee.medicalagent.auth.*;
import com.jarylee.medicalagent.file.JdbcProjectFileRepository;
import com.jarylee.medicalagent.file.ProjectFileRepository;
import com.jarylee.medicalagent.research.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "livePostgres", matches = "true")
class PostgresIdentityAndMembershipLiveTest {

    @Test
    void persistsIdentitySessionAuditAndProjectMembership() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL("jdbc:postgresql://127.0.0.1:5432/medical_agent");
        dataSource.setUser("medical_agent");
        var jdbc = JdbcClient.create(dataSource);
        var identities = new JdbcIdentityRepository(jdbc);
        var auditRepository = new JdbcAuditRepository(jdbc);
        var authentication = new AuthenticationService(
                identities, new BCryptPasswordEncoder(4), Clock.systemUTC(),
                new AuditService(auditRepository));
        var projects = new JdbcProjectRepository(jdbc);
        var members = new JdbcProjectMemberRepository(jdbc);
        var files = new JdbcProjectFileRepository(jdbc);

        UUID hospitalA = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        String password = "InitialPass123";
        String hash = new BCryptPasswordEncoder(4).encode(password);
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalA, "LIVE-ID-A-" + hospitalA, "Live Identity A", Instant.now()));
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalB, "LIVE-ID-B-" + hospitalB, "Live Identity B", Instant.now()));
        identities.insertUser(user(ownerId, hospitalA, "owner-" + ownerId, hash));
        identities.insertUser(user(viewerId, hospitalA, "viewer-" + viewerId, hash));
        identities.insertUser(user(outsiderId, hospitalB, "outsider-" + outsiderId, hash));

        UUID projectId = null;
        try {
            var login = authentication.login("LIVE-ID-A-" + hospitalA, "owner-" + ownerId, password);
            var reconstructedAuthentication = new AuthenticationService(
                    identities, new BCryptPasswordEncoder(4), Clock.systemUTC(),
                    new AuditService(auditRepository));
            assertThat(reconstructedAuthentication.authenticateToken(login.token()).userId())
                    .isEqualTo(ownerId);

            var project = projects.insert(hospitalA, "LIVE-MEMBER-" + ownerId, "Membership verification");
            projectId = project.id();
            members.add(hospitalA, project.id(), ownerId, ProjectMemberRole.OWNER);
            members.add(hospitalA, project.id(), viewerId, ProjectMemberRole.VIEWER);
            files.save(new ProjectFileRepository.FileData(
                    UUID.randomUUID(), hospitalA, project.id(), "verification.txt",
                    hospitalA + "/" + project.id() + "/quarantine/verification.txt",
                    "text/plain", 12, "0".repeat(64), "SAFE", "",
                    "BASIC_SIGNATURE", 12, "EXTRACTED", Instant.now()));

            assertThat(members.findRole(hospitalA, project.id(), ownerId))
                    .contains(ProjectMemberRole.OWNER);
            assertThat(members.findRole(hospitalA, project.id(), viewerId))
                    .contains(ProjectMemberRole.VIEWER);
            assertThat(members.findRole(hospitalA, project.id(), outsiderId)).isEmpty();
            Integer audits = jdbc.sql("""
                    select count(*) from operation_audit
                    where actor_user_id=:userId and action='LOGIN_SUCCESS'
                    """).param("userId", ownerId).query(Integer.class).single();
            assertThat(audits).isEqualTo(1);
        } finally {
            jdbc.sql("delete from user_session where user_id in (:owner,:viewer,:outsider)")
                    .params(Map.of("owner", ownerId, "viewer", viewerId, "outsider", outsiderId)).update();
            jdbc.sql("delete from operation_audit where actor_user_id in (:owner,:viewer,:outsider)")
                    .params(Map.of("owner", ownerId, "viewer", viewerId, "outsider", outsiderId)).update();
            if (projectId != null) {
                jdbc.sql("delete from project_file where project_id=:projectId")
                        .param("projectId", projectId).update();
                jdbc.sql("delete from project_member where project_id=:projectId")
                        .param("projectId", projectId).update();
                jdbc.sql("delete from research_project where id=:projectId")
                        .param("projectId", projectId).update();
            }
            jdbc.sql("delete from user_role where user_id in (:owner,:viewer,:outsider)")
                    .params(Map.of("owner", ownerId, "viewer", viewerId, "outsider", outsiderId)).update();
            jdbc.sql("delete from platform_user where id in (:owner,:viewer,:outsider)")
                    .params(Map.of("owner", ownerId, "viewer", viewerId, "outsider", outsiderId)).update();
            jdbc.sql("delete from hospital where id in (:hospitalA,:hospitalB)")
                    .params(Map.of("hospitalA", hospitalA, "hospitalB", hospitalB)).update();
        }
    }

    private IdentityRepository.UserData user(
            UUID id, UUID hospitalId, String username, String passwordHash) {
        return new IdentityRepository.UserData(id, hospitalId, username, passwordHash,
                Set.of(Role.DOCTOR), true, false, 0, null);
    }
}
