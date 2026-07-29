package com.jarylee.medicalagent.research;

import com.jarylee.medicalagent.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "livePostgres", matches = "true")
class JdbcProjectRepositoryLiveTest {

    @Test
    void enforcesHospitalIsolationIdempotencyAndOptimisticLockOnLocalPostgres() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL("jdbc:postgresql://127.0.0.1:5432/medical_agent");
        dataSource.setUser("medical_agent");
        var jdbc = JdbcClient.create(dataSource);
        var repository = new JdbcProjectRepository(jdbc);

        UUID hospitalA = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        jdbc.sql("insert into hospital(id,code,name) values(:id,:code,:name)")
                .params(Map.of("id", hospitalA, "code", "LIVE-A-" + hospitalA, "name", "Live Hospital A"))
                .update();
        jdbc.sql("insert into hospital(id,code,name) values(:id,:code,:name)")
                .params(Map.of("id", hospitalB, "code", "LIVE-B-" + hospitalB, "name", "Live Hospital B"))
                .update();
        jdbc.sql("""
                insert into platform_user(id,hospital_id,username,password_hash,force_password_change)
                values(:id,:hospitalId,:username,'not-used-in-this-test',false)
                """).params(Map.of("id", userA, "hospitalId", hospitalA, "username", "live-" + userA))
                .update();

        try {
            var project = repository.insert(hospitalA, "LIVE-001", "Local PostgreSQL verification");
            assertThat(repository.findById(hospitalB, project.id())).isEmpty();
            assertThat(repository.findAll(hospitalB)).isEmpty();

            repository.saveIdempotency(hospitalA, userA, "PROJECT_CREATE", "live-key", project.id());
            assertThat(repository.findIdempotentResource(
                    hospitalA, userA, "PROJECT_CREATE", "live-key")).contains(project.id());

            var updated = repository.update(hospitalA, project.id(), "Updated name", 0);
            assertThat(updated.version()).isEqualTo(1);
            assertThatThrownBy(() -> repository.update(hospitalA, project.id(), "Stale update", 0))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("其他用户修改");
            assertThatThrownBy(() -> repository.update(hospitalB, project.id(), "Cross-hospital update", 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("课题不存在");
        } finally {
            jdbc.sql("delete from idempotency_record where hospital_id=:hospitalId")
                    .param("hospitalId", hospitalA).update();
            jdbc.sql("delete from research_project where hospital_id in (:hospitalA,:hospitalB)")
                    .params(Map.of("hospitalA", hospitalA, "hospitalB", hospitalB)).update();
            jdbc.sql("delete from platform_user where id=:id").param("id", userA).update();
            jdbc.sql("delete from hospital where id in (:hospitalA,:hospitalB)")
                    .params(Map.of("hospitalA", hospitalA, "hospitalB", hospitalB)).update();
        }
    }
}
