package com.jarylee.medicalagent.infrastructure;

import com.jarylee.medicalagent.review.ExpertReviewRepository;
import com.jarylee.medicalagent.review.JdbcExpertReviewRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "livePostgresFlyway", matches = "true")
class LocalPostgresThreePartyReviewLiveTest {

    @Test
    void enforcesTriadRoundInvalidationAndAppendOnlyDecisions() throws Exception {
        String database = "medical_agent_flyway_"
                + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
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

            UUID hospitalId = UUID.randomUUID();
            UUID ownerId = UUID.randomUUID();
            UUID medicalId = UUID.randomUUID();
            UUID statisticalId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            UUID protocolId = UUID.randomUUID();
            UUID strobeId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            Instant now = Instant.parse("2026-07-30T08:00:00Z");

            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into hospital(id,code,name)
                        values('%s','REVIEW-LIVE','三方审核测试医院')
                        """.formatted(hospitalId));
                statement.executeUpdate("""
                        insert into platform_user(
                            id,hospital_id,username,password_hash,force_password_change
                        ) values
                        ('%s','%s','owner','unused',false),
                        ('%s','%s','medical','unused',false),
                        ('%s','%s','statistician','unused',false)
                        """.formatted(
                        ownerId, hospitalId, medicalId, hospitalId,
                        statisticalId, hospitalId));
                statement.executeUpdate("""
                        insert into research_project(
                            id,hospital_id,project_key,project_code,project_name
                        ) values(
                            '%s','%s','prj_0123456789ABCDEFGHJKMNPQRS',
                            'REVIEW-LIVE-001','三方审核测试课题'
                        )
                        """.formatted(projectId, hospitalId));
                statement.executeUpdate("""
                        insert into ai_agent_task(
                            id,hospital_id,project_id,current_step,status,
                            input_json,output_json,created_by,cancel_requested,
                            version,created_at,updated_at
                        ) values(
                            '%s','%s','%s','STEP_17_WAIT_EXPERT_REVIEW',
                            'WAITING_CONFIRMATION','{}'::jsonb,
                            '{"protocolDraft":{"title":"匿名方案"}}'::jsonb,
                            '%s',false,0,current_timestamp,current_timestamp
                        )
                        """.formatted(taskId, hospitalId, projectId, ownerId));
                statement.executeUpdate("""
                        insert into research_protocol(
                            id,hospital_id,project_id,agent_task_id,status,study_type,
                            title,schema_version,generator_version,input_sha256,
                            issues_to_confirm_json,result_json,created_at,updated_at
                        ) values(
                            '%s','%s','%s','%s','WAITING_REVIEW','COHORT',
                            '匿名方案','protocol/v1','generator/v1','%s',
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
            }

            var dataSource = new PGSimpleDataSource();
            dataSource.setURL(databaseUrl);
            dataSource.setUser("medical_agent");
            var repository = new JdbcExpertReviewRepository(
                    JdbcClient.create(dataSource));
            String firstHash = "a".repeat(64);
            var created = repository.create(new ExpertReviewRepository.ReviewTaskData(
                    reviewId, hospitalId, projectId, taskId, protocolId, strobeId,
                    "WAITING_EXPERT_REVIEW", 1, firstHash, false,
                    ownerId, now, null, null, null, null,
                    null, null, null, null, null, null, false, 0));

            var medical = repository.decide(
                    hospitalId, reviewId, "MEDICAL_REVIEW", medicalId,
                    "APPROVE", "医学审核通过", firstHash, now, created.version())
                    .orElseThrow();
            assertThat(medical.status()).isEqualTo("WAITING_EXPERT_REVIEW");
            assertThat(repository.ownerConfirmAndLock(
                    hospitalId, reviewId, ownerId, firstHash, now, medical.version()))
                    .isEmpty();
            assertThat(repository.decide(
                    hospitalId, reviewId, "STATISTICAL_REVIEW", medicalId,
                    "APPROVE", "同一账号不得兼任", firstHash, now, medical.version()))
                    .isEmpty();
            repository.addDecision(decision(
                    hospitalId, reviewId, 1, "MEDICAL_REVIEW",
                    medicalId, firstHash, now));

            var both = repository.decide(
                    hospitalId, reviewId, "STATISTICAL_REVIEW", statisticalId,
                    "APPROVE", "统计审核通过", firstHash, now, medical.version())
                    .orElseThrow();
            repository.addDecision(decision(
                    hospitalId, reviewId, 1, "STATISTICAL_REVIEW",
                    statisticalId, firstHash, now));
            assertThat(both.status()).isEqualTo("EXPERT_APPROVED");

            String secondHash = "b".repeat(64);
            var secondRound = repository.resetForNewRound(
                    hospitalId, reviewId, secondHash, now.plusSeconds(1), both.version())
                    .orElseThrow();
            assertThat(secondRound.roundNo()).isEqualTo(2);
            assertThat(secondRound.expertDecision()).isNull();
            assertThat(secondRound.statisticalDecision()).isNull();

            var medicalRoundTwo = repository.decide(
                    hospitalId, reviewId, "MEDICAL_REVIEW", medicalId,
                    "APPROVE", "第二轮医学审核通过", secondHash,
                    now.plusSeconds(2), secondRound.version()).orElseThrow();
            var bothRoundTwo = repository.decide(
                    hospitalId, reviewId, "STATISTICAL_REVIEW", statisticalId,
                    "APPROVE", "第二轮统计审核通过", secondHash,
                    now.plusSeconds(3), medicalRoundTwo.version()).orElseThrow();
            var approved = repository.ownerConfirmAndLock(
                    hospitalId, reviewId, ownerId, secondHash,
                    now.plusSeconds(4), bothRoundTwo.version()).orElseThrow();
            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(approved.sectionsLocked()).isTrue();

            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("""
                        update research_review_decision
                        set summary='tampered'
                        where review_task_id='%s'
                        """.formatted(reviewId)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
                try (var rows = statement.executeQuery("""
                        select count(*)
                        from research_review_decision
                        where review_task_id='%s'
                        """.formatted(reviewId))) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                }
                statement.executeUpdate("""
                        delete from research_review_task
                        where id='%s'
                        """.formatted(reviewId));
            }
        } finally {
            try (var connection = DriverManager.getConnection(adminUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "drop database if exists " + database + " with (force)");
            }
        }
    }

    private ExpertReviewRepository.ReviewDecisionData decision(
            UUID hospitalId,
            UUID reviewId,
            int round,
            String responsibility,
            UUID reviewerId,
            String contentHash,
            Instant decidedAt) {
        return new ExpertReviewRepository.ReviewDecisionData(
                UUID.randomUUID(), hospitalId, reviewId, round, responsibility,
                reviewerId, "APPROVE", "审核通过", contentHash, decidedAt);
    }
}
