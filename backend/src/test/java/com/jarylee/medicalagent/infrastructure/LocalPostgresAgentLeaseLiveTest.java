package com.jarylee.medicalagent.infrastructure;

import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.AgentToolCallRepository;
import com.jarylee.medicalagent.workflow.AgentToolCallService;
import com.jarylee.medicalagent.workflow.JdbcAgentToolCallRepository;
import com.jarylee.medicalagent.workflow.JdbcAgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.JdbcModelCallAuditRepository;
import com.jarylee.medicalagent.workflow.ModelCallAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "livePostgresFlyway", matches = "true")
class LocalPostgresAgentLeaseLiveTest {

    @Test
    void twoWorkersCannotClaimTheSameTaskConcurrently() throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seedTask(databaseUrl);
            var first = repository(databaseUrl);
            var second = repository(databaseUrl);
            Instant now = Instant.parse("2026-07-30T08:00:00Z");
            try (var executor = Executors.newFixedThreadPool(2)) {
                var start = new java.util.concurrent.CountDownLatch(1);
                Callable<java.util.Optional<AgentWorkflowRepository.ClaimedTask>> claimA = () -> {
                    start.await();
                    return first.claimNext(now, "worker-a", Duration.ofSeconds(45));
                };
                Callable<java.util.Optional<AgentWorkflowRepository.ClaimedTask>> claimB = () -> {
                    start.await();
                    return second.claimNext(now, "worker-b", Duration.ofSeconds(45));
                };
                var resultA = executor.submit(claimA);
                var resultB = executor.submit(claimB);
                start.countDown();
                assertThat(List.of(resultA.get(), resultB.get())
                        .stream().filter(java.util.Optional::isPresent).count())
                        .isEqualTo(1);
            }
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_step_attempt
                    where task_id='%s' and status='RUNNING'
                    """.formatted(seed.taskId()))).isEqualTo(1);
        });
    }

    @Test
    void heartbeatExtendsLeaseAndPreventsPrematureReclaim() throws Exception {
        withDatabase(databaseUrl -> {
            seedTask(databaseUrl);
            var repository = repository(databaseUrl);
            Instant firstAt = Instant.parse("2026-07-30T08:00:00Z");
            var claimed = repository.claimNext(
                    firstAt, "worker-heartbeat", Duration.ofSeconds(5))
                    .orElseThrow();

            assertThat(repository.heartbeat(
                    claimed.claim(), firstAt.plusSeconds(49),
                    firstAt.plusSeconds(4))).isTrue();
            assertThat(repository.claimNext(
                    firstAt.plusSeconds(6), "worker-too-early",
                    Duration.ofSeconds(45))).isEmpty();
            assertThat(repository.claimNext(
                    firstAt.plusSeconds(50), "worker-after-expiry",
                    Duration.ofSeconds(45))).isPresent();
        });
    }

    @Test
    void heartbeatAttemptMismatchRollsBackTaskLeaseRenewal() throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seedTask(databaseUrl);
            var dataSource = new DriverManagerDataSource(
                    databaseUrl, "medical_agent", "");
            var repository = new JdbcAgentWorkflowRepository(
                    JdbcClient.create(dataSource));
            var transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            Instant firstAt = Instant.parse("2026-07-30T08:00:00Z");
            var claimed = repository.claimNext(
                    firstAt, "worker-heartbeat-rollback",
                    Duration.ofSeconds(45)).orElseThrow();

            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         update ai_agent_step_attempt
                         set execution_token=?
                         where id=?
                         """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, claimed.claim().stepAttemptId());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }

            assertThatThrownBy(() -> transaction.execute(status ->
                    repository.heartbeat(
                            claimed.claim(), firstAt.plusSeconds(99),
                            firstAt.plusSeconds(1))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("心跳");
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_task
                    where id='%s'
                      and lease_until='2026-07-30T08:00:45Z'::timestamptz
                    """.formatted(seed.taskId()))).isEqualTo(1);
        });
    }

    @Test
    void runningTaskCancellationClosesAttemptAndRejectsStaleCommit()
            throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seedTask(databaseUrl);
            var dataSource = new DriverManagerDataSource(
                    databaseUrl, "medical_agent", "");
            var repository = new JdbcAgentWorkflowRepository(
                    JdbcClient.create(dataSource));
            var transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            Instant now = Instant.parse("2026-07-30T08:00:00Z");
            var claimed = repository.claimNext(
                    now, "worker-cancel", Duration.ofSeconds(45))
                    .orElseThrow();

            Boolean cancelled = transaction.execute(status -> repository.cancel(
                    seed.hospitalId(), seed.taskId(), now.plusSeconds(1)));
            assertThat(cancelled).isTrue();
            assertThat(repository.findById(seed.hospitalId(), seed.taskId())
                    .orElseThrow().status()).isEqualTo("CANCELLED");
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_step_attempt
                    where task_id='%s' and status='CANCELLED'
                    """.formatted(seed.taskId()))).isEqualTo(1);
            assertThat(repository.commitClaim(
                    claimed.claim(), List.of(),
                    new AgentWorkflowRepository.TaskTransition(
                            "STEP_03_ASK_CLARIFICATION",
                            "WAITING_CONFIRMATION", "{}", null),
                    List.of(), now.plusSeconds(2)).status())
                    .isEqualTo(AgentWorkflowRepository.CommitStatus.STALE_TOKEN);
        });
    }

    @Test
    void taskTimeoutFailsAttemptAndWritesOneStableEvent() throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seedTask(databaseUrl);
            var dataSource = new DriverManagerDataSource(
                    databaseUrl, "medical_agent", "");
            var repository = new JdbcAgentWorkflowRepository(
                    JdbcClient.create(dataSource));
            var transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            Instant now = Instant.parse("2026-07-30T08:00:00Z");
            repository.claimNext(
                    now.minusSeconds(1), "worker-timeout",
                    Duration.ofSeconds(45)).orElseThrow();
            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         update ai_agent_task set timeout_at=? where id=?
                         """)) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setObject(2, seed.taskId());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            var timedOut = repository.findById(
                    seed.hospitalId(), seed.taskId()).orElseThrow();
            var timeoutEvent = new AgentWorkflowRepository.PendingEvent(
                    "timeout:v" + timedOut.version(), "TASK_FAILED",
                    timedOut.currentStep(), "{\"errorCode\":\"TASK_TIMEOUT\"}");

            var applied = transaction.execute(status ->
                    repository.failTimedOut(
                            seed.hospitalId(), seed.taskId(),
                            timedOut.version(), now, timeoutEvent));
            var repeated = transaction.execute(status ->
                    repository.failTimedOut(
                            seed.hospitalId(), seed.taskId(),
                            timedOut.version(), now, timeoutEvent));

            assertThat(applied.status())
                    .isEqualTo(AgentWorkflowRepository.CommitStatus.APPLIED);
            assertThat(repeated.status())
                    .isEqualTo(AgentWorkflowRepository.CommitStatus.STALE_TOKEN);
            var failed = repository.findById(
                    seed.hospitalId(), seed.taskId()).orElseThrow();
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.lastErrorCode()).isEqualTo("TASK_TIMEOUT");
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_step_attempt
                    where task_id='%s' and status='FAILED'
                      and error_code='TASK_TIMEOUT'
                    """.formatted(seed.taskId()))).isEqualTo(1);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_event
                    where task_id='%s' and event_key='%s'
                    """.formatted(seed.taskId(), timeoutEvent.stableKey())))
                    .isEqualTo(1);
        });
    }

    @Test
    void expiredWorkerCannotWriteAfterReclaimAndCommitIsIdempotent() throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seedTask(databaseUrl);
            var repository = repository(databaseUrl);
            Instant firstAt = Instant.parse("2026-07-30T08:00:00Z");
            var oldClaim = repository.claimNext(
                    firstAt, "worker-old", Duration.ofSeconds(5)).orElseThrow();
            var newClaim = repository.claimNext(
                    firstAt.plusSeconds(6), "worker-new", Duration.ofSeconds(45))
                    .orElseThrow();

            var oldOutcome = repository.commitClaim(
                    oldClaim.claim(),
                    List.of(step(seed, oldClaim.claim(), firstAt.plusSeconds(1))),
                    new AgentWorkflowRepository.TaskTransition(
                            "STEP_03_ASK_CLARIFICATION", "WAITING_CONFIRMATION",
                            "{\"owner\":\"old\"}", null),
                    List.of(event(oldClaim.claim(), "old")),
                    firstAt.plusSeconds(7));
            assertThat(oldOutcome.status())
                    .isEqualTo(AgentWorkflowRepository.CommitStatus.STALE_TOKEN);

            var newStep = step(seed, newClaim.claim(), firstAt.plusSeconds(8));
            var transition = new AgentWorkflowRepository.TaskTransition(
                    "STEP_03_ASK_CLARIFICATION", "WAITING_CONFIRMATION",
                    "{\"owner\":\"new\"}", null);
            var pending = List.of(event(newClaim.claim(), "new"));
            var applied = repository.commitClaim(
                    newClaim.claim(), List.of(newStep), transition, pending,
                    firstAt.plusSeconds(9));
            var repeated = repository.commitClaim(
                    newClaim.claim(), List.of(newStep), transition, pending,
                    firstAt.plusSeconds(10));

            assertThat(applied.status())
                    .isEqualTo(AgentWorkflowRepository.CommitStatus.APPLIED);
            assertThat(repeated.status())
                    .isEqualTo(AgentWorkflowRepository.CommitStatus.ALREADY_APPLIED);
            assertThat(repeated.events()).hasSize(1);
            assertThat(repository.findById(seed.hospitalId(), seed.taskId()).orElseThrow()
                    .outputJson()).contains("\"owner\": \"new\"");
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_step_run where task_id='%s'
                    """.formatted(seed.taskId()))).isEqualTo(1);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_event
                    where task_id='%s' and event_key like '%%:completed'
                    """.formatted(seed.taskId()))).isEqualTo(1);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_step_attempt
                    where task_id='%s' and status='LEASE_LOST'
                    """.formatted(seed.taskId()))).isEqualTo(1);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_step_attempt
                    where task_id='%s' and status='COMPLETED'
                    """.formatted(seed.taskId()))).isEqualTo(1);
        });
    }

    @Test
    void completedToolOperationIsReusedAfterLeaseReclaim() throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seedTask(databaseUrl);
            var workflows = repository(databaseUrl);
            Instant firstAt = Instant.parse("2026-07-30T08:00:00Z");
            var first = workflows.claimNext(
                    firstAt, "worker-old", Duration.ofSeconds(5)).orElseThrow();
            var toolCalls = new AgentToolCallService(
                    new JdbcAgentToolCallRepository(JdbcClient.create(
                            new DriverManagerDataSource(
                                    databaseUrl, "medical_agent", ""))),
                    new ObjectMapper(),
                    Clock.fixed(firstAt, ZoneOffset.UTC));
            AtomicInteger invocations = new AtomicInteger();
            String initial = toolCalls.invoke(
                    first.claim(), "ANONYMOUS_READ_TOOL",
                    Map.of("query", "anonymous"), String.class,
                    () -> "result-" + invocations.incrementAndGet());

            var reclaimed = workflows.claimNext(
                    firstAt.plusSeconds(6), "worker-new", Duration.ofSeconds(45))
                    .orElseThrow();
            String reused = toolCalls.invoke(
                    reclaimed.claim(), "ANONYMOUS_READ_TOOL",
                    Map.of("query", "anonymous"), String.class,
                    () -> "result-" + invocations.incrementAndGet());

            assertThat(initial).isEqualTo("result-1");
            assertThat(reused).isEqualTo("result-1");
            assertThat(invocations).hasValue(1);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_tool_call
                    where task_id='%s' and status='COMPLETED'
                    """.formatted(seed.taskId()))).isEqualTo(1);
        });
    }

    @Test
    void runningToolOperationRejectsConcurrentReclaimAttempt() throws Exception {
        withDatabase(databaseUrl -> {
            seedTask(databaseUrl);
            var workflows = repository(databaseUrl);
            var toolCalls = new JdbcAgentToolCallRepository(JdbcClient.create(
                    new DriverManagerDataSource(databaseUrl, "medical_agent", "")));
            Instant firstAt = Instant.parse("2026-07-30T08:00:00Z");
            var first = workflows.claimNext(
                    firstAt, "worker-old", Duration.ofSeconds(5)).orElseThrow();
            String operationKey = "a".repeat(64);
            String requestHash = "b".repeat(64);
            var running = new AgentToolCallRepository.ToolCallData(
                    UUID.randomUUID(), first.claim().hospitalId(),
                    first.claim().taskId(), first.claim().stepAttemptId(),
                    first.claim().stepCode(), first.claim().attemptNo(),
                    "ANONYMOUS_READ_TOOL", operationKey, requestHash,
                    "RUNNING", null, null, null, firstAt, null);
            assertThat(toolCalls.begin(running).acquired()).isTrue();

            var reclaimed = workflows.claimNext(
                    firstAt.plusSeconds(6), "worker-new",
                    Duration.ofSeconds(45)).orElseThrow();
            var duplicate = new AgentToolCallRepository.ToolCallData(
                    UUID.randomUUID(), reclaimed.claim().hospitalId(),
                    reclaimed.claim().taskId(), reclaimed.claim().stepAttemptId(),
                    reclaimed.claim().stepCode(), reclaimed.claim().attemptNo(),
                    "ANONYMOUS_READ_TOOL", operationKey, requestHash,
                    "RUNNING", null, null, null, firstAt.plusSeconds(6), null);

            assertThatThrownBy(() -> toolCalls.begin(duplicate))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("拒绝重复调用");
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_tool_call
                    where task_id='%s' and status='RUNNING'
                    """.formatted(first.claim().taskId()))).isEqualTo(1);
        });
    }

    @Test
    void failedEventInsertRollsBackTaskAttemptAndStepTogether() throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seedTask(databaseUrl);
            var dataSource = new DriverManagerDataSource(
                    databaseUrl, "medical_agent", "");
            var repository = new JdbcAgentWorkflowRepository(
                    JdbcClient.create(dataSource));
            var transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            Instant now = Instant.parse("2026-07-30T08:00:00Z");
            var claimed = repository.claimNext(
                    now, "worker-atomic", Duration.ofSeconds(45)).orElseThrow();
            var validStep = step(seed, claimed.claim(), now.plusSeconds(1));
            var invalidEvent = new AgentWorkflowRepository.PendingEvent(
                    claimed.claim().stepAttemptId() + ":invalid",
                    "STEP_COMPLETED", claimed.claim().stepCode(), "{invalid-json");

            assertThatThrownBy(() -> transaction.execute(status ->
                    repository.commitClaim(
                            claimed.claim(), List.of(validStep),
                            new AgentWorkflowRepository.TaskTransition(
                                    "STEP_03_ASK_CLARIFICATION",
                                    "WAITING_CONFIRMATION",
                                    "{\"owner\":\"atomic\"}", null),
                            List.of(invalidEvent), now.plusSeconds(2))))
                    .isInstanceOf(RuntimeException.class);

            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_step_run where task_id='%s'
                    """.formatted(seed.taskId()))).isZero();
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_step_attempt
                    where task_id='%s' and status='RUNNING'
                    """.formatted(seed.taskId()))).isEqualTo(1);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_agent_task
                    where id='%s' and status='RUNNING'
                      and execution_token is not null
                    """.formatted(seed.taskId()))).isEqualTo(1);
        });
    }

    @Test
    void modelAuditRetentionPurgesDatabasePayloadsAndFailsClosedForObjectPayloads()
            throws Exception {
        withDatabase(databaseUrl -> {
            Seed seed = seedTask(databaseUrl);
            var audits = new JdbcModelCallAuditRepository(JdbcClient.create(
                    new DriverManagerDataSource(
                            databaseUrl, "medical_agent", "")));
            Instant now = Instant.parse("2026-07-30T08:00:00Z");
            UUID payloadOnly = UUID.randomUUID();
            UUID metadataExpired = UUID.randomUUID();
            UUID objectBlocked = UUID.randomUUID();

            completeAudit(audits, auditCall(
                    payloadOnly, seed, null, now.minusSeconds(1),
                    now.plusSeconds(60)), now);
            completeAudit(audits, auditCall(
                    metadataExpired, seed, null, now.minusSeconds(1),
                    now.minusSeconds(1)), now);
            completeAudit(audits, auditCall(
                    objectBlocked, seed, "private/model-call/" + objectBlocked,
                    now.minusSeconds(1), now.minusSeconds(1)), now);

            assertThat(audits.purgeExpiredPayloadSnapshots(now, 500))
                    .isEqualTo(2);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_model_call_log
                    where id='%s' and input_snapshot_json is null
                      and output_snapshot_json is null
                      and payload_purged_at='2026-07-30T08:00:00Z'::timestamptz
                    """.formatted(payloadOnly))).isEqualTo(1);
            assertThat(audits.purgeExpiredMetadata(now, 500)).isEqualTo(1);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_model_call_log where id='%s'
                    """.formatted(metadataExpired))).isZero();
            assertThat(audits.countExpiredObjectPayloads(now)).isEqualTo(1);
            assertThat(count(databaseUrl, """
                    select count(*) from ai_model_call_log
                    where id='%s' and input_snapshot_json is not null
                      and raw_payload_object_key is not null
                    """.formatted(objectBlocked))).isEqualTo(1);
        });
    }

    private AgentWorkflowRepository.StepData step(
            Seed seed, AgentWorkflowRepository.ClaimHandle claim, Instant completedAt) {
        return new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), seed.hospitalId(), seed.taskId(), claim.stepCode(),
                claim.attemptNo(), "COMPLETED", "test/input-v1", "test/output-v1",
                "{\"anonymous\":true}", "{\"worker\":\"new\"}", null, null, "[]",
                null, null, completedAt.minusSeconds(1), completedAt,
                false, null, null);
    }

    private AgentWorkflowRepository.PendingEvent event(
            AgentWorkflowRepository.ClaimHandle claim, String owner) {
        return new AgentWorkflowRepository.PendingEvent(
                claim.stepAttemptId() + ":completed", "STEP_COMPLETED",
                claim.stepCode(), "{\"owner\":\"" + owner + "\"}");
    }

    private void completeAudit(
            JdbcModelCallAuditRepository repository,
            ModelCallAuditRepository.ModelCallData call,
            Instant completedAt) {
        repository.start(call);
        repository.succeed(
                call.id(), "b".repeat(64), "{\"controlled\":true}",
                completedAt);
    }

    private ModelCallAuditRepository.ModelCallData auditCall(
            UUID id, Seed seed, String objectKey,
            Instant payloadRetention, Instant metadataRetention) {
        Instant startedAt = Instant.parse("2026-07-29T08:00:00Z");
        return new ModelCallAuditRepository.ModelCallData(
                id, seed.hospitalId(), seed.projectId(), seed.taskId(),
                "STEP_01_PARSE_IDEA", 1, "test", "test-model",
                "step01-parse-idea/v1", "research-model-input/v1",
                "research-analysis/v1", "a".repeat(64), null,
                "{\"anonymous\":true}", null, objectKey, null,
                "REQUESTED", null, null, startedAt, null,
                payloadRetention, metadataRetention);
    }

    private JdbcAgentWorkflowRepository repository(String databaseUrl) {
        var dataSource = new DriverManagerDataSource(databaseUrl, "medical_agent", "");
        return new JdbcAgentWorkflowRepository(JdbcClient.create(dataSource));
    }

    private Seed seedTask(String databaseUrl) throws Exception {
        UUID hospitalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-30T08:00:00Z");
        try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
             var statement = connection.prepareStatement("""
                     insert into hospital(id,code,name)
                     values(?,?,'匿名租约测试医院');
                     insert into research_project(
                         id,hospital_id,project_key,project_code,project_name
                     ) values(?,?,?,?,'匿名租约测试课题');
                     insert into ai_agent_task(
                         id,hospital_id,project_id,current_step,status,input_json,
                         timeout_at,cancel_requested,version,created_at,updated_at
                     ) values(
                         ?,?,?,'STEP_01_PARSE_IDEA','QUEUED',
                         '{"idea":"anonymous"}'::jsonb,?,false,0,?,?
                     )
                     """)) {
            statement.setObject(1, hospitalId);
            statement.setString(
                    2, "LEASE-" + taskId.toString().substring(0, 8).toUpperCase(Locale.ROOT));
            statement.setObject(3, projectId);
            statement.setObject(4, hospitalId);
            statement.setString(5, "prj_0123456789ABCDEFGHJKMNPQRS");
            statement.setString(
                    6, "LEASE-" + projectId.toString().substring(0, 8).toUpperCase(Locale.ROOT));
            statement.setObject(7, taskId);
            statement.setObject(8, hospitalId);
            statement.setObject(9, projectId);
            statement.setTimestamp(10, Timestamp.from(now.plus(Duration.ofHours(1))));
            statement.setTimestamp(11, Timestamp.from(now));
            statement.setTimestamp(12, Timestamp.from(now));
            statement.execute();
        }
        return new Seed(hospitalId, projectId, taskId);
    }

    private long count(String databaseUrl, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void withDatabase(CheckedConsumer<String> test) throws Exception {
        String database = "medical_agent_lease_" +
                UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        String adminUrl = "jdbc:postgresql://127.0.0.1:5432/postgres";
        String adminUser = System.getenv().getOrDefault("POSTGRES_ADMIN_USER", "postgres");
        try (var connection = DriverManager.getConnection(adminUrl, adminUser, "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("create database " + database + " owner medical_agent");
        }
        try {
            String databaseUrl = "jdbc:postgresql://127.0.0.1:5432/" + database;
            try (var connection = DriverManager.getConnection(databaseUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("create extension if not exists vector");
            }
            Flyway.configure()
                    .dataSource(databaseUrl, "medical_agent", "")
                    .load().migrate();
            test.accept(databaseUrl);
        } finally {
            try (var connection = DriverManager.getConnection(adminUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "drop database if exists " + database + " with (force)");
            }
        }
    }

    private record Seed(UUID hospitalId, UUID projectId, UUID taskId) {}

    @FunctionalInterface
    private interface CheckedConsumer<T> {
        void accept(T value) throws Exception;
    }
}
