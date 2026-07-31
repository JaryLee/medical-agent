package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
    public List<SectionData> findSections(
            UUID hospitalId, UUID protocolId) {
        return jdbc.sql("""
                select id,hospital_id,protocol_id,section_code,title,
                       sort_order,current_version_no,status
                from research_protocol_section
                where hospital_id=:hospitalId and protocol_id=:protocolId
                order by sort_order
                """)
                .param("hospitalId", hospitalId)
                .param("protocolId", protocolId)
                .query((result, row) -> new SectionData(
                        result.getObject("id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getObject("protocol_id", UUID.class),
                        result.getString("section_code"),
                        result.getString("title"),
                        result.getInt("sort_order"),
                        result.getInt("current_version_no"),
                        result.getString("status")))
                .list();
    }

    @Override
    public List<SectionVersionData> findSectionVersions(
            UUID hospitalId, UUID sectionId) {
        return jdbc.sql("""
                select id,hospital_id,section_id,version_no,content,
                       content_format,origin,evidence_status,
                       source_identifiers_json::text,
                       issues_to_confirm_json::text,
                       change_reason,created_by,created_at
                from research_protocol_section_version
                where hospital_id=:hospitalId and section_id=:sectionId
                order by version_no
                """)
                .param("hospitalId", hospitalId)
                .param("sectionId", sectionId)
                .query(this::mapVersion)
                .list();
    }

    @Override
    public List<ProjectSectionVersionData> findProjectSectionVersions(
            UUID hospitalId, UUID projectId, String sectionCode) {
        return jdbc.sql("""
                select s.section_code,s.title,p.created_at protocol_created_at,
                       v.version_no,v.content,v.content_format,v.origin,
                       v.evidence_status,v.change_reason,v.created_at
                from research_protocol p
                join research_protocol_section s
                  on s.hospital_id=p.hospital_id
                 and s.protocol_id=p.id
                join research_protocol_section_version v
                  on v.hospital_id=s.hospital_id
                 and v.section_id=s.id
                where p.hospital_id=:hospitalId
                  and p.project_id=:projectId
                  and s.section_code=:sectionCode
                order by p.created_at,v.version_no,v.created_at
                """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .param("sectionCode", sectionCode)
                .query((result, row) -> new ProjectSectionVersionData(
                        result.getString("section_code"),
                        result.getString("title"),
                        result.getTimestamp(
                                "protocol_created_at").toInstant(),
                        result.getInt("version_no"),
                        result.getString("content"),
                        result.getString("content_format"),
                        result.getString("origin"),
                        result.getString("evidence_status"),
                        result.getString("change_reason"),
                        result.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    @Transactional
    public Optional<SectionVersionData> appendSectionVersion(
            UUID hospitalId,
            UUID protocolId,
            UUID sectionId,
            int expectedVersionNo,
            String content,
            String origin,
            String changeReason,
            UUID createdBy,
            Instant createdAt) {
        List<SectionVersionData> current = jdbc.sql("""
                select v.id,v.hospital_id,v.section_id,v.version_no,v.content,
                       v.content_format,v.origin,v.evidence_status,
                       v.source_identifiers_json::text,
                       v.issues_to_confirm_json::text,
                       v.change_reason,v.created_by,v.created_at
                from research_protocol_section s
                join research_protocol_section_version v
                  on v.hospital_id=s.hospital_id
                 and v.section_id=s.id
                 and v.version_no=s.current_version_no
                where s.hospital_id=:hospitalId
                  and s.protocol_id=:protocolId
                  and s.id=:sectionId
                  and s.status='DRAFT'
                  and s.current_version_no=:expectedVersion
                for update of s
                """)
                .param("hospitalId", hospitalId)
                .param("protocolId", protocolId)
                .param("sectionId", sectionId)
                .param("expectedVersion", expectedVersionNo)
                .query(this::mapVersion)
                .list();
        if (current.isEmpty()) return Optional.empty();
        SectionVersionData previous = current.get(0);
        if (previous.content().equals(content)) {
            throw new IllegalArgumentException("章节内容没有变化");
        }
        int nextVersion = expectedVersionNo + 1;
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
                insert into research_protocol_section_version(
                    id,hospital_id,section_id,version_no,content,
                    content_format,origin,evidence_status,
                    source_identifiers_json,issues_to_confirm_json,
                    change_reason,created_by,created_at
                ) values(
                    :id,:hospitalId,:sectionId,:versionNo,:content,
                    :contentFormat,:origin,:evidenceStatus,
                    cast(:sources as jsonb),cast(:issues as jsonb),
                    :changeReason,:createdBy,:createdAt
                )
                """)
                .param("id", versionId)
                .param("hospitalId", hospitalId)
                .param("sectionId", sectionId)
                .param("versionNo", nextVersion)
                .param("content", content)
                .param("contentFormat", previous.contentFormat())
                .param("origin", origin)
                .param("evidenceStatus", previous.evidenceStatus())
                .param("sources", previous.sourceIdentifiersJson())
                .param("issues", previous.issuesToConfirmJson())
                .param("changeReason", changeReason)
                .param("createdBy", createdBy)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        int updated = jdbc.sql("""
                update research_protocol_section
                set current_version_no=:nextVersion,
                    updated_at=:updatedAt,
                    version=version+1
                where hospital_id=:hospitalId
                  and protocol_id=:protocolId
                  and id=:sectionId
                  and status='DRAFT'
                  and current_version_no=:expectedVersion
                """)
                .param("nextVersion", nextVersion)
                .param("updatedAt", Timestamp.from(createdAt))
                .param("hospitalId", hospitalId)
                .param("protocolId", protocolId)
                .param("sectionId", sectionId)
                .param("expectedVersion", expectedVersionNo)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("方案章节版本并发更新失败");
        }
        return Optional.of(new SectionVersionData(
                versionId, hospitalId, sectionId, nextVersion, content,
                previous.contentFormat(), origin, previous.evidenceStatus(),
                previous.sourceIdentifiersJson(), previous.issuesToConfirmJson(),
                changeReason, createdBy, createdAt));
    }

    @Override
    public boolean updateResultSnapshot(
            UUID hospitalId,
            UUID protocolId,
            String resultJson,
            Instant updatedAt) {
        return jdbc.sql("""
                update research_protocol
                set result_json=cast(:resultJson as jsonb),
                    updated_at=:updatedAt,
                    version=version+1
                where hospital_id=:hospitalId
                  and id=:protocolId
                  and status in ('DRAFT','WAITING_REVIEW')
                """)
                .param("resultJson", resultJson)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("hospitalId", hospitalId)
                .param("protocolId", protocolId)
                .update() == 1;
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

    private SectionVersionData mapVersion(
            ResultSet result, int row) throws SQLException {
        return new SectionVersionData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("section_id", UUID.class),
                result.getInt("version_no"),
                result.getString("content"),
                result.getString("content_format"),
                result.getString("origin"),
                result.getString("evidence_status"),
                result.getString("source_identifiers_json"),
                result.getString("issues_to_confirm_json"),
                result.getString("change_reason"),
                result.getObject("created_by", UUID.class),
                result.getTimestamp("created_at").toInstant());
    }
}
