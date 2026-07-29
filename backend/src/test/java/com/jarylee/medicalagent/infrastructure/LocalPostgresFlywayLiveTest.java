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
                assertThat(tables.getInt(1)).isEqualTo(45);
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
                             'research_review_action','document_template_version',
                             'document_export_record','citation_style_version'
                           )
                         """);
                 var tables = statement.executeQuery()) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getInt(1)).isEqualTo(29);
            }
        } finally {
            try (var connection = DriverManager.getConnection(adminUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("drop database if exists " + database + " with (force)");
            }
        }
    }
}
