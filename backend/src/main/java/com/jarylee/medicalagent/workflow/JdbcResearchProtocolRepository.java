package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcResearchProtocolRepository implements ResearchProtocolRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcResearchProtocolRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ProtocolData> findByAgentTask(
            UUID hospitalId, UUID agentTaskId) {
        return jdbc.sql("""
                select id,hospital_id,project_id,agent_task_id,status,study_type,title,
                    schema_version,generator_version,input_sha256,
                    issues_to_confirm_json::text,result_json::text,created_at
                from research_protocol
                where hospital_id=:hospitalId and agent_task_id=:agentTaskId
                """)
                .param("hospitalId", hospitalId)
                .param("agentTaskId", agentTaskId)
                .query((result, rowNum) -> new ProtocolData(
                        result.getObject("id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getObject("project_id", UUID.class),
                        result.getObject("agent_task_id", UUID.class),
                        result.getString("status"),
                        result.getString("study_type"),
                        result.getString("title"),
                        result.getString("schema_version"),
                        result.getString("generator_version"),
                        result.getString("input_sha256"),
                        result.getString("issues_to_confirm_json"),
                        result.getString("result_json"),
                        result.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional
    public void save(
            ProtocolData protocol,
            List<ResearchProtocolModels.ProtocolSection> sections) {
        jdbc.sql("""
                insert into research_protocol(
                    id,hospital_id,project_id,agent_task_id,status,study_type,title,
                    schema_version,generator_version,input_sha256,
                    issues_to_confirm_json,result_json,created_at,updated_at
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:status,:studyType,:title,
                    :schemaVersion,:generatorVersion,:inputSha256,
                    cast(:issues as jsonb),cast(:result as jsonb),:createdAt,:createdAt
                )
                """)
                .param("id", protocol.id())
                .param("hospitalId", protocol.hospitalId())
                .param("projectId", protocol.projectId())
                .param("agentTaskId", protocol.agentTaskId())
                .param("status", protocol.status())
                .param("studyType", protocol.studyType())
                .param("title", protocol.title())
                .param("schemaVersion", protocol.schemaVersion())
                .param("generatorVersion", protocol.generatorVersion())
                .param("inputSha256", protocol.inputSha256())
                .param("issues", protocol.issuesToConfirmJson())
                .param("result", protocol.resultJson())
                .param("createdAt", Timestamp.from(protocol.createdAt()))
                .update();
        for (var section : sections) {
            jdbc.sql("""
                    insert into research_protocol_section(
                        id,hospital_id,protocol_id,section_code,title,sort_order,
                        current_version_no,status,created_at,updated_at
                    ) values(
                        :id,:hospitalId,:protocolId,:sectionCode,:title,:sortOrder,
                        :versionNo,'DRAFT',:createdAt,:createdAt
                    )
                    """)
                    .param("id", section.sectionId())
                    .param("hospitalId", protocol.hospitalId())
                    .param("protocolId", protocol.id())
                    .param("sectionCode", section.sectionCode())
                    .param("title", section.title())
                    .param("sortOrder", section.sortOrder())
                    .param("versionNo", section.versionNo())
                    .param("createdAt", Timestamp.from(protocol.createdAt()))
                    .update();
            jdbc.sql("""
                    insert into research_protocol_section_version(
                        id,hospital_id,section_id,version_no,content,content_format,
                        origin,evidence_status,source_identifiers_json,
                        issues_to_confirm_json,change_reason,created_at
                    ) values(
                        gen_random_uuid(),:hospitalId,:sectionId,:versionNo,:content,
                        :contentFormat,:origin,:evidenceStatus,
                        cast(:sourceIdentifiers as jsonb),cast(:issues as jsonb),
                        'INITIAL_AGENT_GENERATION',:createdAt
                    )
                    """)
                    .param("hospitalId", protocol.hospitalId())
                    .param("sectionId", section.sectionId())
                    .param("versionNo", section.versionNo())
                    .param("content", section.content())
                    .param("contentFormat", section.contentFormat())
                    .param("origin", section.origin())
                    .param("evidenceStatus", section.evidenceStatus())
                    .param("sourceIdentifiers", writeList(section.sourceIdentifiers()))
                    .param("issues", writeList(section.issuesToConfirm()))
                    .param("createdAt", Timestamp.from(protocol.createdAt()))
                    .update();
        }
    }

    private String writeList(List<String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (Exception exception) {
            throw new IllegalStateException("研究方案章节元数据序列化失败", exception);
        }
    }
}
