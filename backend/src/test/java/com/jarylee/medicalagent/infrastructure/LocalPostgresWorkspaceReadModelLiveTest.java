package com.jarylee.medicalagent.infrastructure;

import com.jarylee.medicalagent.workflow.JdbcAgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.JdbcResearchProtocolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.workspace.JdbcWorkspaceRepository;
import com.jarylee.medicalagent.workspace.WorkspaceRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "livePostgresFlyway", matches = "true")
class LocalPostgresWorkspaceReadModelLiveTest {

    @Test
    void upgradesV26ProjectsWithAnInitialCursorAndReplayEvent()
            throws Exception {
        withDatabase("26", databaseUrl -> {
            Seed seed = seed(databaseUrl);
            Flyway.configure()
                    .dataSource(databaseUrl, "medical_agent", "")
                    .load()
                    .migrate();

            Repositories repositories = repositories(databaseUrl);
            WorkspaceRepository.Cursor cursor =
                    repositories.workspace().requireCursor(
                            seed.hospitalId(), seed.projectId(),
                            Instant.parse("2026-07-30T08:00:00Z"));
            assertThat(cursor.readModelVersion()).isEqualTo(1);
            assertThat(repositories.workspace().findEventsAfter(
                    seed.hospitalId(), seed.projectId(), 0, 10))
                    .singleElement()
                    .extracting(
                            WorkspaceRepository.ProjectEventData::readModelVersion,
                            WorkspaceRepository.ProjectEventData::eventType)
                    .containsExactly(1L, "PROJECT_READ_MODEL_CHANGED");
        });
    }

    @Test
    void triggersVersionsEventsAndPersistsIdempotentCommands() throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seed(databaseUrl);
            Repositories repositories = repositories(databaseUrl);
            Instant now = Instant.parse("2026-07-30T08:00:00Z");

            WorkspaceRepository.Cursor initial =
                    repositories.workspace().requireCursor(
                            seed.hospitalId(), seed.projectId(), now);
            assertThat(initial.readModelVersion()).isEqualTo(1);
            assertThat(repositories.workspace().earliestEventId(
                    seed.hospitalId(), seed.projectId())).contains(
                    initial.latestEventId());

            repositories.agent().appendEvent(
                    seed.hospitalId(), seed.taskId(),
                    "TASK_CREATED", "STEP_01_PARSE_IDEA",
                    "{\"status\":\"QUEUED\"}", now.plusSeconds(1));
            WorkspaceRepository.Cursor afterAgent =
                    repositories.workspace().requireCursor(
                            seed.hospitalId(), seed.projectId(), now);
            assertThat(afterAgent.readModelVersion()).isEqualTo(2);
            assertThat(afterAgent.latestEventId())
                    .isGreaterThan(initial.latestEventId());

            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         update research_project
                         set project_name='匿名工作台课题（更新）'
                         where hospital_id=? and id=?
                         """)) {
                statement.setObject(1, seed.hospitalId());
                statement.setObject(2, seed.projectId());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            WorkspaceRepository.Cursor afterProject =
                    repositories.workspace().requireCursor(
                            seed.hospitalId(), seed.projectId(), now);
            assertThat(afterProject.readModelVersion()).isEqualTo(3);
            List<WorkspaceRepository.ProjectEventData> events =
                    repositories.workspace().findEventsAfter(
                            seed.hospitalId(), seed.projectId(), 0, 10);
            assertThat(events)
                    .extracting(WorkspaceRepository.ProjectEventData::readModelVersion)
                    .containsExactly(1L, 2L, 3L);

            UUID commandId = UUID.randomUUID();
            WorkspaceRepository.CommandDraft command = draft(
                    commandId, seed, "START_RESEARCH_IDEA",
                    "workspace-live-command-0001", 3, now.plusSeconds(2));
            assertThat(repositories.workspace().reserve(command).acquired()).isTrue();
            WorkspaceRepository.Reservation replay =
                    repositories.workspace().reserve(command);
            assertThat(replay.acquired()).isFalse();
            assertThat(replay.versionConflict()).isFalse();
            assertThat(replay.existing().status()).isEqualTo("RUNNING");

            repositories.workspace().completeCommand(
                    seed.hospitalId(), commandId, 3,
                    "{\"data\":{\"projectKey\":\""
                            + seed.projectKey()
                            + "\"},\"meta\":{\"readModelVersion\":3}}",
                    now.plusSeconds(3));
            WorkspaceRepository.CommandData completed =
                    repositories.workspace().findCommand(
                                    seed.hospitalId(), seed.projectId(),
                                    seed.userId(), "workspace-live-command-0001")
                            .orElseThrow();
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.resultReadModelVersion()).isEqualTo(3);

            WorkspaceRepository.Reservation stale =
                    repositories.workspace().reserve(draft(
                            UUID.randomUUID(), seed,
                            "CANCEL_RESEARCH_WORKFLOW",
                            "workspace-live-stale-0001", 2,
                            now.plusSeconds(4)));
            assertThat(stale.versionConflict()).isTrue();
            assertThat(repositories.workspace().findEventsAfter(
                    seed.otherHospitalId(), seed.projectId(), 0, 10)).isEmpty();
        });
    }

    @Test
    void serializesConcurrentCommandsAgainstTheProjectVersion()
            throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seed(databaseUrl);
            Instant now = Instant.parse("2026-07-30T08:30:00Z");
            Repositories first = repositories(databaseUrl);
            Repositories second = repositories(databaseUrl);
            long version = first.workspace().requireCursor(
                    seed.hospitalId(), seed.projectId(), now)
                    .readModelVersion();
            CountDownLatch firstChanged = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);

            try (var executor = Executors.newFixedThreadPool(2)) {
                var firstResult = executor.submit(() ->
                        first.transaction().execute(status -> {
                            WorkspaceRepository.Reservation reservation =
                                    first.workspace().reserve(draft(
                                            UUID.randomUUID(), seed,
                                            "START_RESEARCH_IDEA",
                                            "workspace-concurrent-a-0001",
                                            version, now));
                            first.agent().appendEvent(
                                    seed.hospitalId(), seed.taskId(),
                                    "TASK_CREATED", "STEP_01_PARSE_IDEA",
                                    "{\"status\":\"QUEUED\"}",
                                    now.plusSeconds(1));
                            firstChanged.countDown();
                            await(releaseFirst);
                            return reservation;
                        }));
                assertThat(firstChanged.await(
                        10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

                var secondResult = executor.submit(() -> {
                    secondStarted.countDown();
                    return second.transaction().execute(status ->
                            second.workspace().reserve(draft(
                                    UUID.randomUUID(), seed,
                                    "CANCEL_RESEARCH_WORKFLOW",
                                    "workspace-concurrent-b-0001",
                                    version, now.plusSeconds(2))));
                });
                assertThat(secondStarted.await(
                        10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                releaseFirst.countDown();

                assertThat(firstResult.get().acquired()).isTrue();
                WorkspaceRepository.Reservation losing = secondResult.get();
                assertThat(losing.acquired()).isFalse();
                assertThat(losing.versionConflict()).isTrue();
            }
        });
    }

    @Test
    void serializesConcurrentProtocolSectionRevisionsAndScopesHistory()
            throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seed(databaseUrl);
            UUID protocolId = UUID.randomUUID();
            UUID sectionId = UUID.randomUUID();
            seedProtocol(databaseUrl, seed, protocolId, sectionId);
            Repositories first = repositories(databaseUrl);
            Repositories second = repositories(databaseUrl);
            Instant now = Instant.parse("2026-07-30T09:00:00Z");
            CountDownLatch firstAppended = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            try (var executor = Executors.newFixedThreadPool(2)) {
                var firstResult = executor.submit(() ->
                        first.transaction().execute(status -> {
                            var appended = first.protocol()
                                    .appendSectionVersion(
                                            seed.hospitalId(),
                                            protocolId,
                                            sectionId,
                                            1,
                                            "第一位编辑者提交的匿名修订",
                                            "HUMAN",
                                            "并发编辑 A",
                                            seed.userId(),
                                            now);
                            firstAppended.countDown();
                            await(releaseFirst);
                            return appended;
                        }));
                assertThat(firstAppended.await(
                        10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

                var secondResult = executor.submit(() ->
                        second.transaction().execute(status ->
                                second.protocol().appendSectionVersion(
                                        seed.hospitalId(),
                                        protocolId,
                                        sectionId,
                                        1,
                                        "第二位编辑者提交的匿名修订",
                                        "HUMAN",
                                        "并发编辑 B",
                                        seed.userId(),
                                        now.plusSeconds(1))));
                releaseFirst.countDown();

                assertThat(firstResult.get()).isPresent();
                assertThat(secondResult.get()).isEmpty();
            }

            assertThat(first.protocol().findSectionVersions(
                    seed.hospitalId(), sectionId))
                    .extracting(value -> value.versionNo())
                    .containsExactly(1, 2);
            assertThat(first.protocol().findProjectSectionVersions(
                    seed.hospitalId(), seed.projectId(), "BACKGROUND"))
                    .hasSize(2);
            assertThat(first.protocol().findProjectSectionVersions(
                    seed.otherHospitalId(), seed.projectId(), "BACKGROUND"))
                    .isEmpty();

            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         update research_protocol_section
                         set status='LOCKED'
                         where hospital_id=? and id=?
                         """)) {
                statement.setObject(1, seed.hospitalId());
                statement.setObject(2, sectionId);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            java.util.Optional<?> lockedResult =
                    first.transaction().execute(status ->
                            first.protocol().appendSectionVersion(
                                    seed.hospitalId(),
                                    protocolId,
                                    sectionId,
                                    2,
                                    "锁定后不应保存的内容",
                                    "HUMAN",
                                    "锁定负向测试",
                                    seed.userId(),
                                    now.plusSeconds(2)));
            assertThat(lockedResult).isEmpty();
        });
    }

    private WorkspaceRepository.CommandDraft draft(
            UUID id,
            Seed seed,
            String action,
            String idempotencyKey,
            long version,
            Instant createdAt) {
        return new WorkspaceRepository.CommandDraft(
                id, seed.hospitalId(), seed.projectId(), seed.userId(),
                action, idempotencyKey, "a".repeat(64), version, createdAt);
    }

    private Repositories repositories(String databaseUrl) {
        var dataSource = new DriverManagerDataSource(
                databaseUrl, "medical_agent", "");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        return new Repositories(
                new JdbcWorkspaceRepository(jdbc),
                new JdbcAgentWorkflowRepository(jdbc),
                new JdbcResearchProtocolRepository(
                        jdbc, new ObjectMapper().findAndRegisterModules()),
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)));
    }

    private void seedProtocol(
            String databaseUrl,
            Seed seed,
            UUID protocolId,
            UUID sectionId) throws Exception {
        Instant now = Instant.parse("2026-07-30T08:59:00Z");
        try (var connection = DriverManager.getConnection(
                databaseUrl, "medical_agent", "");
             var statement = connection.prepareStatement("""
                     insert into research_protocol(
                         id,hospital_id,project_id,agent_task_id,status,
                         study_type,title,schema_version,generator_version,
                         input_sha256,issues_to_confirm_json,result_json,
                         created_at,updated_at,version
                     ) values(
                         ?,?,?,?,'DRAFT','COHORT','匿名研究方案',
                         'protocol/v1','deterministic/v1',?,
                         '[]'::jsonb,'{}'::jsonb,?,?,0
                     );
                     insert into research_protocol_section(
                         id,hospital_id,protocol_id,section_code,title,
                         sort_order,current_version_no,status,
                         created_at,updated_at,version
                     ) values(
                         ?,?,?,'BACKGROUND','研究背景',
                         1,1,'DRAFT',?,?,0
                     );
                     insert into research_protocol_section_version(
                         id,hospital_id,section_id,version_no,content,
                         content_format,origin,evidence_status,
                         source_identifiers_json,issues_to_confirm_json,
                         change_reason,created_at
                     ) values(
                         ?,?,?,1,'匿名初始内容','MARKDOWN',
                         'AGENT_DETERMINISTIC','NEEDS_EXPERT_REVIEW',
                         '[]'::jsonb,'[]'::jsonb,
                         'INITIAL_AGENT_GENERATION',?
                     )
                     """)) {
            int index = 1;
            statement.setObject(index++, protocolId);
            statement.setObject(index++, seed.hospitalId());
            statement.setObject(index++, seed.projectId());
            statement.setObject(index++, seed.taskId());
            statement.setString(index++, "a".repeat(64));
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setObject(index++, sectionId);
            statement.setObject(index++, seed.hospitalId());
            statement.setObject(index++, protocolId);
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setObject(index++, UUID.randomUUID());
            statement.setObject(index++, seed.hospitalId());
            statement.setObject(index++, sectionId);
            statement.setTimestamp(index, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private Seed seed(String databaseUrl) throws Exception {
        UUID hospitalId = UUID.randomUUID();
        UUID otherHospitalId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String projectKey = "prj_0123456789ABCDEFGHJKMNPQRS";
        Instant now = Instant.parse("2026-07-30T07:59:00Z");
        try (var connection = DriverManager.getConnection(
                databaseUrl, "medical_agent", "");
             var statement = connection.prepareStatement("""
                     insert into hospital(id,code,name) values
                     (?,?,'匿名工作台医院'),
                     (?,?,'另一匿名医院');
                     insert into platform_user(
                         id,hospital_id,username,password_hash,
                         force_password_change
                     ) values(?,?,'workspace-owner','not-used',false);
                     insert into research_project(
                         id,hospital_id,project_key,project_code,project_name,
                         created_at
                     ) values(?,?,?,'WORKSPACE-LIVE','匿名工作台课题',?);
                     insert into project_member(
                         hospital_id,project_id,user_id,member_role
                     ) values(?,?,?,'OWNER');
                     insert into ai_agent_task(
                         id,hospital_id,project_id,current_step,status,input_json,
                         timeout_at,cancel_requested,version,created_at,updated_at,
                         created_by
                     ) values(
                         ?,?,?,'STEP_01_PARSE_IDEA','QUEUED',
                         '{"idea":"anonymous"}'::jsonb,?,false,0,?,?,?
                     )
                     """)) {
            int index = 1;
            statement.setObject(index++, hospitalId);
            statement.setString(index++, "WORKSPACE-" + shortId(hospitalId));
            statement.setObject(index++, otherHospitalId);
            statement.setString(index++, "WORKSPACE-" + shortId(otherHospitalId));
            statement.setObject(index++, userId);
            statement.setObject(index++, hospitalId);
            statement.setObject(index++, projectId);
            statement.setObject(index++, hospitalId);
            statement.setString(index++, projectKey);
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setObject(index++, hospitalId);
            statement.setObject(index++, projectId);
            statement.setObject(index++, userId);
            statement.setObject(index++, taskId);
            statement.setObject(index++, hospitalId);
            statement.setObject(index++, projectId);
            statement.setTimestamp(index++,
                    Timestamp.from(now.plusSeconds(3600)));
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setObject(index, userId);
            statement.executeUpdate();
        }
        return new Seed(
                hospitalId, otherHospitalId, userId,
                projectId, taskId, projectKey);
    }

    private void withDatabase(DatabaseWork work) throws Exception {
        withDatabase(null, work);
    }

    private void withDatabase(String target, DatabaseWork work)
            throws Exception {
        String database = "medical_agent_workspace_"
                + UUID.randomUUID().toString().replace("-", "")
                .toLowerCase(Locale.ROOT);
        String adminUrl = "jdbc:postgresql://127.0.0.1:5432/postgres";
        String adminUser = System.getenv()
                .getOrDefault("POSTGRES_ADMIN_USER", "postgres");
        try (var connection = DriverManager.getConnection(
                adminUrl, adminUser, "");
             var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create database " + database + " owner medical_agent");
        }
        try {
            String databaseUrl =
                    "jdbc:postgresql://127.0.0.1:5432/" + database;
            try (var connection = DriverManager.getConnection(
                    databaseUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "create extension if not exists vector");
            }
            var flyway = Flyway.configure()
                    .dataSource(databaseUrl, "medical_agent", "");
            if (target != null) {
                flyway.target(MigrationVersion.fromVersion(target));
            }
            flyway.load().migrate();
            work.run(databaseUrl);
        } finally {
            try (var connection = DriverManager.getConnection(
                    adminUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "drop database if exists "
                                + database + " with (force)");
            }
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", exception);
        }
    }

    private String shortId(UUID value) {
        return value.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface DatabaseWork {
        void run(String databaseUrl) throws Exception;
    }

    private record Repositories(
            JdbcWorkspaceRepository workspace,
            JdbcAgentWorkflowRepository agent,
            JdbcResearchProtocolRepository protocol,
            TransactionTemplate transaction) {}

    private record Seed(
            UUID hospitalId,
            UUID otherHospitalId,
            UUID userId,
            UUID projectId,
            UUID taskId,
            String projectKey) {}
}
