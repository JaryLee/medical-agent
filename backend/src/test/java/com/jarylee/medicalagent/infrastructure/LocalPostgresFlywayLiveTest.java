package com.jarylee.medicalagent.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "livePostgresFlyway", matches = "true")
class LocalPostgresFlywayLiveTest {

    @Test
    void createsCurrentSchemaFromAnEmptyLocalDatabase() throws Exception {
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
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.tables
                         where table_schema='public'
                         """);
                 var tables = statement.executeQuery()) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getInt(1)).isEqualTo(59);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.columns
                         where table_schema='public'
                           and table_name='ai_agent_clarification_round'
                           and column_name in (
                             'round_no','source_step','questions_json','answers_json',
                             'submitted_by','submitted_at'
                           )
                         """);
                 var columns = statement.executeQuery()) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt(1)).isEqualTo(6);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.columns
                         where table_schema='public' and table_name='project_file'
                           and column_name in ('scan_engine','extracted_characters','extraction_status')
                         """);
                 var columns = statement.executeQuery()) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt(1)).isEqualTo(3);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.tables
                         where table_schema='public'
                           and table_name in (
                             'literature_search_task','literature_record','project_literature',
                             'clinical_trial_record','project_clinical_trial',
                             'literature_validation_task','citation_validation_record',
                             'evidence_source_link','similar_research_analysis_task',
                             'similar_research_comparison','research_gap_suggestion',
                             'observational_design_recommendation_task',
                             'observational_design_alternative',
                             'research_protocol','research_protocol_section',
                             'research_protocol_section_version',
                             'statistical_analysis_draft',
                             'sample_size_parameter_requirement',
                             'claim_citation_validation_task',
                             'research_claim','claim_citation_link',
                             'strobe_completeness_check_task',
                             'strobe_completeness_check_item',
                             'research_review_task','research_review_comment',
                             'research_review_action','research_review_decision',
                             'document_template_version',
                             'document_export_record','citation_style_version'
                           )
                         """);
                 var tables = statement.executeQuery()) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getInt(1)).isEqualTo(30);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.columns
                         where table_schema='public' and table_name='ai_model_call_log'
                           and column_name in (
                             'input_sha256','output_sha256','input_snapshot_json',
                             'output_snapshot_json','status','payload_retention_until',
                             'metadata_retention_until','logical_model_type',
                             'route_policy_version','provider_request_id','usage_source',
                             'input_tokens','cached_input_tokens','output_tokens','total_tokens',
                             'price_version','price_currency','reserved_cost_micros',
                             'estimated_cost_micros','cost_status'
                           )
                         """);
                 var columns = statement.executeQuery()) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt(1)).isEqualTo(20);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.columns
                         where table_schema='public' and table_name='ai_agent_task'
                           and column_name in (
                             'execution_token','lease_owner','lease_acquired_at',
                             'heartbeat_at','current_step_attempt_id'
                           )
                         """);
                 var columns = statement.executeQuery()) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt(1)).isEqualTo(5);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.columns
                         where table_schema='public'
                           and (
                             (table_name='ai_agent_step_run'
                              and column_name='step_attempt_id')
                             or
                             (table_name='ai_agent_event'
                              and column_name='event_key')
                           )
                         """);
                 var columns = statement.executeQuery()) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt(1)).isEqualTo(2);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.columns
                         where table_schema='public'
                           and table_name='ai_agent_tool_call'
                           and column_name in (
                             'step_attempt_id','tool_call_key','operation_key',
                             'request_sha256','result_json','status'
                           )
                         """);
                 var columns = statement.executeQuery()) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt(1)).isEqualTo(6);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.tables
                         where table_schema='public'
                           and table_name in (
                             'project_workspace_cursor',
                             'project_read_model_event',
                             'project_workspace_command'
                           )
                         """);
                 var tables = statement.executeQuery()) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getInt(1)).isEqualTo(3);
            }
            try (var connection = DriverManager.getConnection(databaseUrl, "medical_agent", "");
                 var statement = connection.prepareStatement("""
                         select count(*) from information_schema.tables
                         where table_schema='public'
                           and table_name in (
                             'project_model_budget',
                             'protocol_section_model_candidate',
                             'protocol_section_model_review',
                             'observational_design_model_advice',
                             'model_evaluation_run',
                             'model_evaluation_case_result',
                             'model_evaluation_expert_score'
                           )
                         """);
                 var tables = statement.executeQuery()) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getInt(1)).isEqualTo(7);
            }
        } finally {
            try (var connection = DriverManager.getConnection(adminUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("drop database if exists " + database + " with (force)");
            }
        }
    }
}
