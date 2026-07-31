package com.jarylee.medicalagent.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "livePostgresFlyway", matches = "true")
class LocalPostgresV19UpgradeLiveTest {

    @Test
    void upgradesV19ModelCallIdsBeforeAddingTheForeignKey() throws Exception {
        String database = "medical_agent_flyway_" +
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
                    .target(MigrationVersion.fromVersion("19"))
                    .load()
                    .migrate();

            UUID hospitalId = UUID.randomUUID();
            UUID ownerId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            UUID protocolId = UUID.randomUUID();
            UUID strobeId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            UUID firstCallId = UUID.randomUUID();
            UUID secondCallId = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into hospital(id,code,name)
                        values('%s','UPGRADE','匿名升级测试医院')
                        """.formatted(hospitalId));
                statement.executeUpdate("""
                        insert into platform_user(
                            id,hospital_id,username,password_hash,force_password_change
                        ) values('%s','%s','legacy-owner','unused',false)
                        """.formatted(ownerId, hospitalId));
                statement.executeUpdate("""
                        insert into research_project(id,hospital_id,project_code,project_name)
                        values('%s','%s','UPGRADE-001','匿名升级测试课题')
                        """.formatted(projectId, hospitalId));
                statement.executeUpdate("""
                        insert into ai_agent_task(
                            id,hospital_id,project_id,current_step,status,input_json,
                            cancel_requested,version,created_at,updated_at
                        ) values(
                            '%s','%s','%s','STEP_05_CONFIRM_DIRECTION',
                            'WAITING_CONFIRMATION','{"idea":"anonymous"}'::jsonb,
                            false,2,current_timestamp,current_timestamp
                        )
                        """.formatted(taskId, hospitalId, projectId));
                statement.executeUpdate("""
                        update ai_agent_task
                        set output_json='{"directions":[{"id":"DIR-01"}]}'::jsonb,
                            created_by='%s'
                        where id='%s'
                        """.formatted(ownerId, taskId));
                statement.executeUpdate("""
                        insert into research_protocol(
                            id,hospital_id,project_id,agent_task_id,status,study_type,
                            title,schema_version,generator_version,input_sha256,
                            issues_to_confirm_json,result_json,created_at,updated_at
                        ) values(
                            '%s','%s','%s','%s','WAITING_REVIEW','COHORT',
                            '旧审核方案','protocol/v1','generator/v1','%s',
                            '[]'::jsonb,'{}'::jsonb,current_timestamp,current_timestamp
                        )
                        """.formatted(
                        protocolId, hospitalId, projectId, taskId, "1".repeat(64)));
                statement.executeUpdate("""
                        insert into strobe_completeness_check_task(
                            id,hospital_id,project_id,agent_task_id,protocol_id,status,
                            study_type,total_item_count,covered_count,
                            partially_covered_count,missing_count,not_applicable_count,
                            needs_expert_review_count,input_sha256,checker_version,
                            result_json,created_at
                        ) values(
                            '%s','%s','%s','%s','%s','COMPLETED','COHORT',
                            22,22,0,0,0,0,'%s','checker/v1','{}'::jsonb,current_timestamp
                        )
                        """.formatted(
                        strobeId, hospitalId, projectId, taskId, protocolId,
                        "2".repeat(64)));
                statement.executeUpdate("""
                        insert into research_review_task(
                            id,hospital_id,project_id,agent_task_id,protocol_id,
                            strobe_check_task_id,status,submitted_by,submitted_at,
                            sections_locked,version,created_at,updated_at
                        ) values(
                            '%s','%s','%s','%s','%s','%s',
                            'WAITING_EXPERT_REVIEW','%s',current_timestamp,
                            false,0,current_timestamp,current_timestamp
                        )
                        """.formatted(
                        reviewId, hospitalId, projectId, taskId, protocolId,
                        strobeId, ownerId));
                statement.executeUpdate("""
                        insert into research_review_action(
                            id,hospital_id,review_task_id,action_type,
                            actor_user_id,summary,occurred_at
                        ) values(
                            '%s','%s','%s','REVIEW_OPENED','%s',
                            '旧审核记录',current_timestamp
                        )
                        """.formatted(
                        UUID.randomUUID(), hospitalId, reviewId, ownerId));
                insertLegacyStep(statement, hospitalId, taskId, firstCallId,
                        "STEP_01_PARSE_IDEA", 1);
                insertLegacyStep(statement, hospitalId, taskId, secondCallId,
                        "STEP_04_GENERATE_RESEARCH_DIRECTIONS", 2);
            }

            Flyway.configure()
                    .dataSource(databaseUrl, "medical_agent", "")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         select count(*) as total,
                                count(*) filter (where status='LEGACY_UNVERIFIED') as legacy,
                                count(*) filter (
                                    where logical_model_type='RESEARCH_FAST'
                                      and route_policy_version='legacy-single-route/v1'
                                      and usage_source='NOT_AVAILABLE'
                                      and cost_status='UNPRICED'
                                      and estimated_cost_micros is null
                                ) as governed_legacy
                         from ai_model_call_log
                         """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("total")).isEqualTo(2);
                assertThat(rows.getInt("legacy")).isEqualTo(2);
                assertThat(rows.getInt("governed_legacy")).isEqualTo(2);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement();
                 var task = statement.executeQuery("""
                         select output_json ->> 'candidateSetId' as candidate_set_id,
                                output_json ->> 'candidateSetHash' as candidate_set_hash
                         from ai_agent_task
                         """)) {
                assertThat(task.next()).isTrue();
                assertThat(task.getString("candidate_set_id")).isNotBlank();
                assertThat(task.getString("candidate_set_hash")).matches("[0-9a-f]{64}");
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement();
                 var project = statement.executeQuery("""
                         select project_key
                         from research_project
                         where id='%s'
                         """.formatted(projectId))) {
                assertThat(project.next()).isTrue();
                assertThat(project.getString("project_key"))
                        .matches("prj_[0-9A-HJKMNP-TV-Z]{26}");
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement();
                 var review = statement.executeQuery("""
                         select status, legacy_review, round_no,
                                review_content_sha256
                         from research_review_task
                         where id='%s'
                         """.formatted(reviewId))) {
                assertThat(review.next()).isTrue();
                assertThat(review.getString("status")).isEqualTo("SUPERSEDED");
                assertThat(review.getBoolean("legacy_review")).isTrue();
                assertThat(review.getInt("round_no")).isEqualTo(1);
                assertThat(review.getString("review_content_sha256"))
                        .matches("[0-9a-f]{64}");
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement();
                 var action = statement.executeQuery("""
                         select review_round_no
                         from research_review_action
                         where review_task_id='%s'
                         """.formatted(reviewId))) {
                assertThat(action.next()).isTrue();
                assertThat(action.getInt("review_round_no")).isEqualTo(1);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement();
                 var workspace = statement.executeQuery("""
                         select cursor_row.read_model_version,
                                cursor_row.latest_event_id,
                                event_row.read_model_version as event_version
                         from project_workspace_cursor cursor_row
                         join project_read_model_event event_row
                           on event_row.hospital_id=cursor_row.hospital_id
                          and event_row.project_id=cursor_row.project_id
                          and event_row.id=cursor_row.latest_event_id
                         where cursor_row.hospital_id='%s'
                           and cursor_row.project_id='%s'
                         """.formatted(hospitalId, projectId))) {
                assertThat(workspace.next()).isTrue();
                assertThat(workspace.getLong("read_model_version")).isEqualTo(1);
                assertThat(workspace.getLong("latest_event_id")).isPositive();
                assertThat(workspace.getLong("event_version")).isEqualTo(1);
            }

            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement()) {
                assertThatThrownBy(() -> insertLegacyStep(
                        statement, hospitalId, taskId, UUID.randomUUID(),
                        "STEP_06_BUILD_RESEARCH_QUESTION", 3))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("fk_agent_step_model_call");
            }
        } finally {
            try (var connection = DriverManager.getConnection(adminUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("drop database if exists " + database + " with (force)");
            }
        }
    }

    private void insertLegacyStep(
            java.sql.Statement statement,
            UUID hospitalId,
            UUID taskId,
            UUID modelCallId,
            String stepCode,
            int attemptNo) throws SQLException {
        statement.executeUpdate("""
                insert into ai_agent_step_run(
                    id,hospital_id,task_id,step_code,attempt_no,status,
                    input_schema_version,output_schema_version,input_json,output_json,
                    model_call_id,prompt_version,tool_calls_json,started_at,completed_at
                ) values(
                    '%s','%s','%s','%s',%d,'COMPLETED',
                    'legacy/input','legacy/output','{"anonymous":true}'::jsonb,
                    '{"anonymous":true}'::jsonb,'%s','legacy/v1','[]'::jsonb,
                    current_timestamp,current_timestamp
                )
                """.formatted(
                UUID.randomUUID(), hospitalId, taskId, stepCode, attemptNo, modelCallId));
    }
}
