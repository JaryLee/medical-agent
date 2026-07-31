package com.jarylee.medicalagent.infrastructure;

import com.jarylee.medicalagent.research.JdbcProjectRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "livePostgresFlyway", matches = "true")
class LocalPostgresTenantConstraintLiveTest {

    @Test
    void rejectsCrossHospitalRelationshipsAndNormalizedDuplicates() throws Exception {
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

            UUID hospitalA = UUID.randomUUID();
            UUID hospitalB = UUID.randomUUID();
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();
            UUID projectA = UUID.randomUUID();
            UUID projectB = UUID.randomUUID();
            String keyA = "prj_0123456789ABCDEFGHJKMNPQRS";
            String keyB = "prj_1123456789ABCDEFGHJKMNPQRS";

            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into hospital(id,code,name) values
                        ('%s','TENANT-A','医院 A'),
                        ('%s','TENANT-B','医院 B')
                        """.formatted(hospitalA, hospitalB));
                statement.executeUpdate("""
                        insert into platform_user(
                            id,hospital_id,username,password_hash,force_password_change
                        ) values
                        ('%s','%s','doctor','not-used',false),
                        ('%s','%s','doctor','not-used',false)
                        """.formatted(userA, hospitalA, userB, hospitalB));
                statement.executeUpdate("""
                        insert into research_project(
                            id,hospital_id,project_key,project_code,project_name
                        ) values
                        ('%s','%s','%s','SAME-CODE','同名课题'),
                        ('%s','%s','%s','SAME-CODE','同名课题')
                        """.formatted(
                        projectA, hospitalA, keyA,
                        projectB, hospitalB, keyB));

                assertThatThrownBy(() -> statement.executeUpdate("""
                        insert into project_member(
                            hospital_id,project_id,user_id,member_role
                        ) values('%s','%s','%s','VIEWER')
                        """.formatted(hospitalA, projectA, userB)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("fk_tenant_");

                assertThatThrownBy(() -> statement.executeUpdate("""
                        insert into project_file(
                            id,hospital_id,project_id,original_name,object_key,
                            content_type,size_bytes,sha256,security_status,matched_rules
                        ) values(
                            '%s','%s','%s','anonymous.txt','tenant/test/object',
                            'text/plain',1,'%s','SAFE',''
                        )
                        """.formatted(
                        UUID.randomUUID(), hospitalA, projectB, "0".repeat(64))))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("fk_tenant_");

                assertThatThrownBy(() -> statement.executeUpdate("""
                        insert into platform_user(
                            id,hospital_id,username,password_hash,force_password_change
                        ) values('%s','%s','Doctor','not-used',false)
                        """.formatted(UUID.randomUUID(), hospitalA)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining(
                                "uk_user_hospital_username_normalized");

                assertThatThrownBy(() -> statement.executeUpdate("""
                        update research_project
                        set project_key='prj_2123456789ABCDEFGHJKMNPQRS'
                        where id='%s'
                        """.formatted(projectA)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("project_key is immutable");
            }

            var dataSource = new org.postgresql.ds.PGSimpleDataSource();
            dataSource.setURL(databaseUrl);
            dataSource.setUser("medical_agent");
            var projects = new JdbcProjectRepository(
                    org.springframework.jdbc.core.simple.JdbcClient.create(dataSource));
            assertThat(projects.findByKey(hospitalA, keyA)).isPresent();
            assertThat(projects.findByKey(hospitalB, keyA)).isEmpty();

            try (var connection = DriverManager.getConnection(
                    databaseUrl, "medical_agent", "");
                 var statement = connection.createStatement();
                 var missing = statement.executeQuery("""
                         select count(*)
                         from pg_constraint original
                         join pg_class child on child.oid=original.conrelid
                         join pg_class parent on parent.oid=original.confrelid
                         join pg_namespace namespace on namespace.oid=child.relnamespace
                         where original.contype='f'
                           and namespace.nspname='public'
                           and cardinality(original.conkey)=1
                           and exists (
                             select 1 from pg_attribute attribute_row
                             where attribute_row.attrelid=child.oid
                               and attribute_row.attname='hospital_id'
                               and not attribute_row.attisdropped
                           )
                           and exists (
                             select 1 from pg_attribute attribute_row
                             where attribute_row.attrelid=parent.oid
                               and attribute_row.attname='hospital_id'
                               and not attribute_row.attisdropped
                           )
                           and not exists (
                             select 1
                             from pg_constraint tenant_constraint
                             where tenant_constraint.conrelid=original.conrelid
                               and tenant_constraint.confrelid=original.confrelid
                               and tenant_constraint.contype='f'
                               and cardinality(tenant_constraint.conkey)=2
                           )
                         """)) {
                assertThat(missing.next()).isTrue();
                assertThat(missing.getInt(1)).isZero();
            }
        } finally {
            try (var connection = DriverManager.getConnection(adminUrl, adminUser, "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("drop database if exists " + database + " with (force)");
            }
        }
    }
}
