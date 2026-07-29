package com.jarylee.medicalagent.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg18")
                    .withDatabaseName("medical_agent")
                    .withUsername("medical_agent")
                    .withPassword("test-only");

    @Test
    void migratesAnEmptyPostgresqlDatabase() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                     "select count(*) from information_schema.tables where table_schema='public' "
                             + "and table_name in ('hospital','research_project','ai_agent_task','ai_agent_step_run',"
                             + "'ai_agent_clarification_round','platform_user','project_file',"
                             + "'project_member','operation_audit','literature_validation_task',"
                             + "'citation_validation_record','evidence_source_link',"
                             + "'similar_research_analysis_task','similar_research_comparison',"
                             + "'research_gap_suggestion',"
                             + "'observational_design_recommendation_task',"
                             + "'observational_design_alternative',"
                             + "'research_protocol','research_protocol_section',"
                             + "'research_protocol_section_version',"
                             + "'statistical_analysis_draft',"
                             + "'sample_size_parameter_requirement',"
                             + "'claim_citation_validation_task',"
                             + "'research_claim','claim_citation_link',"
                             + "'strobe_completeness_check_task',"
                             + "'strobe_completeness_check_item',"
                             + "'research_review_task','research_review_comment',"
                             + "'research_review_action','document_template_version',"
                             + "'document_export_record','citation_style_version')");
             var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(33);
        }
    }
}
