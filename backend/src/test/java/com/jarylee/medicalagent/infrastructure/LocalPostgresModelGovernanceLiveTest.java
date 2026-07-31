package com.jarylee.medicalagent.infrastructure;

import com.jarylee.medicalagent.agent.JdbcProtocolModelGovernanceRepository;
import com.jarylee.medicalagent.agent.ProtocolModelGovernanceRepository;
import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelRoute;
import com.jarylee.medicalagent.agent.model.ResearchModel;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.JdbcModelCallAuditRepository;
import com.jarylee.medicalagent.workflow.JdbcProjectModelBudgetRepository;
import com.jarylee.medicalagent.workflow.ModelBudgetService;
import com.jarylee.medicalagent.workflow.ModelCallAuditRepository;
import com.jarylee.medicalagent.workflow.ModelCostCalculator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "livePostgresFlyway", matches = "true")
class LocalPostgresModelGovernanceLiveTest {
    @Test
    void upgradesV27AndEnforcesTenantConcurrencyRetentionAndReviewerIndependence()
            throws Exception {
        withDatabase(databaseUrl -> {
            Flyway.configure()
                    .dataSource(databaseUrl, "medical_agent", "")
                    .target(MigrationVersion.fromVersion("27"))
                    .load()
                    .migrate();
            Flyway.configure()
                    .dataSource(databaseUrl, "medical_agent", "")
                    .load()
                    .migrate();

            Seed seed = seed(databaseUrl);
            var dataSource = new DriverManagerDataSource(
                    databaseUrl, "medical_agent", "");
            var repository = new JdbcProtocolModelGovernanceRepository(
                    JdbcClient.create(dataSource));
            UUID candidateId = UUID.randomUUID();
            repository.saveCandidate(new ProtocolModelGovernanceRepository.CandidateData(
                    candidateId,
                    seed.hospitalId(),
                    seed.projectId(),
                    seed.taskId(),
                    seed.protocolId(),
                    seed.sectionId(),
                    "BACKGROUND",
                    1,
                    seed.modelCallId(),
                    "protocol-section-generation/v1",
                    "这是通过真实 PostgreSQL 验证的合成匿名模型章节候选内容。",
                    "a".repeat(64),
                    "[]",
                    "b".repeat(64),
                    "[\"由专家确认\"]",
                    "{\"status\":\"PASSED\"}",
                    "VALIDATED",
                    Instant.parse("2026-07-30T10:00:00Z"),
                    null,
                    null,
                    null,
                    0));

            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> repository.markCandidateApplied(
                        seed.hospitalId(), candidateId, 0, seed.ownerId(),
                        Instant.parse("2026-07-30T10:01:00Z"), 2));
                var second = executor.submit(() -> repository.markCandidateApplied(
                        seed.hospitalId(), candidateId, 0, seed.ownerId(),
                        Instant.parse("2026-07-30T10:01:00Z"), 2));
                assertThat(List.of(first.get(), second.get()))
                        .containsExactlyInAnyOrder(true, false);
            }
            var applied = repository.findCandidate(
                    seed.hospitalId(), candidateId).orElseThrow();
            assertThat(applied.status()).isEqualTo("APPLIED");
            assertThat(applied.appliedVersionNo()).isEqualTo(2);
            assertThat(applied.version()).isEqualTo(1);

            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        delete from ai_model_call_log where id='%s'
                        """.formatted(seed.modelCallId()));
                try (var rows = statement.executeQuery("""
                        select model_call_id
                        from protocol_section_model_candidate
                        where id='%s'
                        """.formatted(candidateId))) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("model_call_id")).isNull();
                }
            }

            UUID otherHospital = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into hospital(id,code,name)
                        values('%s','GOVB','模型治理测试医院B')
                        """.formatted(otherHospital));
                assertThatThrownBy(() -> statement.executeUpdate("""
                        insert into protocol_section_model_candidate(
                            id,hospital_id,project_id,agent_task_id,protocol_id,
                            section_id,section_code,base_version_no,prompt_version,
                            content,content_sha256,used_evidence_keys_json,
                            allowed_evidence_sha256,issues_to_confirm_json,
                            validation_json,status,generated_at
                        ) values(
                            '%s','%s','%s','%s','%s','%s','BACKGROUND',1,
                            'protocol-section-generation/v1','跨医院候选内容',
                            '%s','[]'::jsonb,'%s','[]'::jsonb,'{}'::jsonb,
                            'VALIDATED',current_timestamp
                        )
                        """.formatted(
                        UUID.randomUUID(), otherHospital, seed.projectId(),
                        seed.taskId(), seed.protocolId(), seed.sectionId(),
                        "c".repeat(64), "d".repeat(64))))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("fk_protocol_model_candidate_project");
            }

            verifyEvaluationReviewerIndependence(databaseUrl, seed);
            verifyInvalidUsageRejected(databaseUrl, seed);
            verifyConcurrentBudgetReservation(databaseUrl, seed);
        });
    }

    private void verifyConcurrentBudgetReservation(
            String databaseUrl, Seed seed) throws Exception {
        var dataSource = new DriverManagerDataSource(
                databaseUrl, "medical_agent", "");
        var calls = new JdbcModelCallAuditRepository(
                JdbcClient.create(dataSource));
        var budgets = new JdbcProjectModelBudgetRepository(
                JdbcClient.create(dataSource));
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-30T10:30:00Z"), ZoneOffset.UTC);
        var firstService = new ModelBudgetService(
                budgets, calls, new ModelCostCalculator(), clock,
                5_000, 5_000, "USD", 4_096);
        var secondService = new ModelBudgetService(
                budgets, calls, new ModelCostCalculator(), clock,
                5_000, 5_000, "USD", 4_096);
        var transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        var task = new AgentWorkflowRepository.TaskData(
                seed.taskId(), seed.hospitalId(), seed.projectId(),
                seed.ownerId(), "BUDGET_TEST", "RUNNING", "{}", "{}",
                null, clock.instant().plusSeconds(60), false, 1,
                null, null, clock.instant(), clock.instant(), null);
        ResearchModel model = new ResearchModel() {
            @Override public AnalysisResult analyzeIdea(String idea) {
                throw new UnsupportedOperationException();
            }
            @Override public String provider() { return "priced-live-test"; }
            @Override public String modelName() { return "priced-live-model"; }
        };
        var route = new ModelRoute(
                LogicalModelType.RESEARCH_STANDARD,
                model,
                "live-budget/v1",
                "LIVE_TEST",
                new ModelRoute.Pricing(
                        "live-price/v1", "USD",
                        1_000_000L, 1_000_000L, 1_000_000L));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reserve(
                    transactions, barrier, firstService, task, route,
                    budgetCall(seed, UUID.randomUUID(), clock.instant())));
            var second = executor.submit(() -> reserve(
                    transactions, barrier, secondService, task, route,
                    budgetCall(seed, UUID.randomUUID(), clock.instant())));
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(
                            "RESERVED", "MODEL_PROJECT_BUDGET_EXCEEDED");
        }
        var usage = calls.projectConsumption(
                seed.hospitalId(), seed.projectId());
        assertThat(usage.activeReservationCostMicros()).isPositive();
        assertThat(usage.callCount()).isEqualTo(1);
    }

    private String reserve(
            TransactionTemplate transactions,
            CyclicBarrier barrier,
            ModelBudgetService service,
            AgentWorkflowRepository.TaskData task,
            ModelRoute route,
            ModelCallAuditRepository.ModelCallData call) {
        try {
            barrier.await();
            transactions.executeWithoutResult(ignored ->
                    service.reserveAndStart(task, route, 1, 1, call));
            return "RESERVED";
        } catch (BusinessException exception) {
            return exception.code();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ModelCallAuditRepository.ModelCallData budgetCall(
            Seed seed, UUID id, Instant now) {
        return new ModelCallAuditRepository.ModelCallData(
                id, seed.hospitalId(), seed.projectId(), seed.taskId(),
                "BUDGET_TEST", 1, "priced-live-test",
                "priced-live-model", "budget-prompt/v1",
                "budget-input/v1", "budget-output/v1",
                "1".repeat(64), null, "{}", null, null, null,
                "REQUESTED", null, null, now, null,
                now.plusSeconds(3600), now.plusSeconds(7200),
                "RESEARCH_STANDARD", "live-budget/v1", "LIVE_TEST",
                null, "NOT_AVAILABLE", null, null, null, null,
                "live-price/v1", "USD", null,
                "USAGE_UNAVAILABLE", null);
    }

    private void verifyEvaluationReviewerIndependence(
            String databaseUrl, Seed seed) throws Exception {
        UUID runId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                databaseUrl, "medical_agent", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into model_evaluation_run(
                        id,hospital_id,started_by,dataset_version,
                        data_classification,prompt_version,route_policy_version,
                        idempotency_key,request_sha256,status,case_count,
                        passed_count,report_sha256,report_json,
                        started_at,completed_at
                    ) values(
                        '%s','%s','%s','anonymous-research-cases/v1',
                        'SYNTHETIC_ANONYMOUS','research-idea-analysis/v1',
                        'mock-routing/v2','live-evaluation-start-0001','%s',
                        'WAITING_EXPERT_SCORING',5,5,
                        '%s','{\"classification\":\"SYNTHETIC_ANONYMOUS\"}'::jsonb,
                        current_timestamp,current_timestamp
                    )
                    """.formatted(
                    runId, seed.hospitalId(), seed.ownerId(),
                    "d".repeat(64), "e".repeat(64)));
            insertScore(
                    statement, runId, seed.hospitalId(), seed.ownerId(),
                    "MEDICAL_REVIEW");
            assertThatThrownBy(() -> insertScore(
                    statement, runId, seed.hospitalId(), seed.ownerId(),
                    "STATISTICAL_REVIEW"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "medical and statistical evaluation reviewers must be different");
        }
    }

    private void verifyInvalidUsageRejected(String databaseUrl, Seed seed)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                databaseUrl, "medical_agent", "");
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    insert into ai_model_call_log(
                        id,hospital_id,project_id,task_id,step_code,attempt_no,
                        provider,model_name,prompt_version,input_schema_version,
                        output_schema_version,input_sha256,status,started_at,
                        metadata_retention_until,usage_source,input_tokens,
                        cached_input_tokens,output_tokens,total_tokens
                    ) values(
                        '%s','%s','%s','%s','PROTOCOL_SECTION_GENERATION',1,
                        'test','test-model','test/v1','input/v1','output/v1',
                        '%s','SUCCEEDED',current_timestamp,
                        current_timestamp + interval '3 years',
                        'PROVIDER_REPORTED',10,11,2,12
                    )
                    """.formatted(
                    UUID.randomUUID(), seed.hospitalId(), seed.projectId(),
                    seed.taskId(), "f".repeat(64))))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_model_call_tokens_non_negative");
        }
    }

    private void insertScore(
            java.sql.Statement statement,
            UUID runId,
            UUID hospitalId,
            UUID reviewerId,
            String responsibility) throws SQLException {
        statement.executeUpdate("""
                insert into model_evaluation_expert_score(
                    id,hospital_id,evaluation_run_id,responsibility,reviewer_id,
                    correctness_score,completeness_score,safety_score,
                    actionability_score,recommendation,comment,
                    idempotency_key,request_sha256,submitted_at
                ) values(
                    '%s','%s','%s','%s','%s',4,4,5,4,'ACCEPT',
                    '合成匿名评测内部评分','score-%s','%s',current_timestamp
                )
                """.formatted(
                UUID.randomUUID(), hospitalId, runId, responsibility,
                reviewerId, UUID.randomUUID(), "c".repeat(64)));
    }

    private Seed seed(String databaseUrl) throws Exception {
        UUID hospitalId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID protocolId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID sectionVersionId = UUID.randomUUID();
        UUID modelCallId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                databaseUrl, "medical_agent", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into hospital(id,code,name)
                    values('%s','GOVA','模型治理测试医院A')
                    """.formatted(hospitalId));
            statement.executeUpdate("""
                    insert into platform_user(
                        id,hospital_id,username,password_hash,force_password_change
                    ) values('%s','%s','governance-owner','unused',false)
                    """.formatted(ownerId, hospitalId));
            statement.executeUpdate("""
                    insert into research_project(
                        id,hospital_id,project_code,project_name,project_key
                    ) values(
                        '%s','%s','GOV-001','合成匿名模型治理课题',
                        'prj_00000000000000000000000000'
                    )
                    """.formatted(projectId, hospitalId));
            statement.executeUpdate("""
                    insert into ai_agent_task(
                        id,hospital_id,project_id,created_by,current_step,status,
                        input_json,output_json,cancel_requested,version,
                        created_at,updated_at
                    ) values(
                        '%s','%s','%s','%s','STEP_17_WAIT_EXPERT_REVIEW',
                        'REVISION_REQUIRED','{\"idea\":\"synthetic\"}'::jsonb,
                        '{}'::jsonb,false,1,current_timestamp,current_timestamp
                    )
                    """.formatted(taskId, hospitalId, projectId, ownerId));
            statement.executeUpdate("""
                    insert into ai_model_call_log(
                        id,hospital_id,project_id,task_id,step_code,attempt_no,
                        provider,model_name,prompt_version,input_schema_version,
                        output_schema_version,input_sha256,output_sha256,status,
                        started_at,completed_at,metadata_retention_until,
                        logical_model_type,route_policy_version,route_reason,
                        usage_source,input_tokens,cached_input_tokens,output_tokens,
                        total_tokens,price_version,price_currency,
                        estimated_cost_micros,cost_status
                    ) values(
                        '%s','%s','%s','%s','PROTOCOL_SECTION_GENERATION',1,
                        'mock','standard-model','protocol-section-generation/v1',
                        'input/v1','output/v1','%s','%s','SUCCEEDED',
                        current_timestamp,current_timestamp,
                        current_timestamp + interval '3 years',
                        'RESEARCH_STANDARD','mock-routing/v2',
                        'DETERMINISTIC_TEST_DEFAULT','SYNTHETIC_TEST',
                        null,null,null,null,null,null,null,'TEST_ONLY'
                    )
                    """.formatted(
                    modelCallId, hospitalId, projectId, taskId,
                    "1".repeat(64), "2".repeat(64)));
            statement.executeUpdate("""
                    insert into research_protocol(
                        id,hospital_id,project_id,agent_task_id,status,study_type,
                        title,schema_version,generator_version,input_sha256,
                        issues_to_confirm_json,result_json,created_at,updated_at
                    ) values(
                        '%s','%s','%s','%s','DRAFT','COHORT',
                        '合成匿名方案','protocol/v1','generator/v1','%s',
                        '[]'::jsonb,'{}'::jsonb,current_timestamp,current_timestamp
                    )
                    """.formatted(
                    protocolId, hospitalId, projectId, taskId, "3".repeat(64)));
            statement.executeUpdate("""
                    insert into research_protocol_section(
                        id,hospital_id,protocol_id,section_code,title,sort_order,
                        current_version_no,status,created_at,updated_at
                    ) values(
                        '%s','%s','%s','BACKGROUND','研究背景',1,1,'DRAFT',
                        current_timestamp,current_timestamp
                    )
                    """.formatted(sectionId, hospitalId, protocolId));
            statement.executeUpdate("""
                    insert into research_protocol_section_version(
                        id,hospital_id,section_id,version_no,content,content_format,
                        origin,evidence_status,source_identifiers_json,
                        issues_to_confirm_json,change_reason,created_by,created_at
                    ) values(
                        '%s','%s','%s',1,'合成匿名章节内容','MARKDOWN',
                        'AGENT_DETERMINISTIC','NEEDS_EXPERT_REVIEW',
                        '[]'::jsonb,'[]'::jsonb,'INITIAL_GENERATION','%s',
                        current_timestamp
                    )
                    """.formatted(
                    sectionVersionId, hospitalId, sectionId, ownerId));
        }
        return new Seed(
                hospitalId, ownerId, projectId, taskId,
                protocolId, sectionId, modelCallId);
    }

    private void withDatabase(DatabaseWork work) throws Exception {
        String database = "medical_agent_model_governance_"
                + UUID.randomUUID().toString().replace("-", "")
                .toLowerCase(Locale.ROOT);
        String adminUrl = "jdbc:postgresql://127.0.0.1:5432/postgres";
        String adminUser = System.getenv()
                .getOrDefault("POSTGRES_ADMIN_USER", "postgres");
        try (var connection = DriverManager.getConnection(adminUrl, adminUser, "");
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
                statement.executeUpdate("create extension if not exists vector");
            }
            work.run(databaseUrl);
        } finally {
            try (var connection = DriverManager.getConnection(
                    adminUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "drop database if exists " + database + " with (force)");
            }
        }
    }

    @FunctionalInterface
    private interface DatabaseWork {
        void run(String databaseUrl) throws Exception;
    }

    private record Seed(
            UUID hospitalId,
            UUID ownerId,
            UUID projectId,
            UUID taskId,
            UUID protocolId,
            UUID sectionId,
            UUID modelCallId) {}
}
