package com.jarylee.medicalagent.document;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcDocumentExportRepository implements DocumentExportRepository {
    private final JdbcClient jdbc;

    public JdbcDocumentExportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int nextTemplateVersion(UUID hospitalId, String templateCode) {
        Integer value = jdbc.sql("""
                select coalesce(max(version_no),0)+1
                from document_template_version
                where hospital_id=:hospitalId and template_code=:templateCode
                """)
                .param("hospitalId", hospitalId)
                .param("templateCode", templateCode)
                .query(Integer.class)
                .single();
        return value == null ? 1 : value;
    }

    @Override
    public TemplateData createTemplate(TemplateData value) {
        jdbc.sql("""
                insert into document_template_version(
                    id,hospital_id,template_code,template_name,version_no,status,
                    object_key,content_sha256,content_size,placeholder_schema_version,
                    placeholders_json,validation_status,validation_message,
                    created_by,created_at,published_by,published_at,version
                ) values(
                    :id,:hospitalId,:templateCode,:templateName,:versionNo,:status,
                    :objectKey,:contentSha256,:contentSize,:placeholderSchemaVersion,
                    cast(:placeholdersJson as jsonb),:validationStatus,:validationMessage,
                    :createdBy,:createdAt,:publishedBy,:publishedAt,:version
                )
                """)
                .param("id", value.id())
                .param("hospitalId", value.hospitalId())
                .param("templateCode", value.templateCode())
                .param("templateName", value.templateName())
                .param("versionNo", value.versionNo())
                .param("status", value.status())
                .param("objectKey", value.objectKey())
                .param("contentSha256", value.contentSha256())
                .param("contentSize", value.contentSize())
                .param("placeholderSchemaVersion", value.placeholderSchemaVersion())
                .param("placeholdersJson", value.placeholdersJson())
                .param("validationStatus", value.validationStatus())
                .param("validationMessage", value.validationMessage())
                .param("createdBy", value.createdBy())
                .param("createdAt", Timestamp.from(value.createdAt()))
                .param("publishedBy", value.publishedBy())
                .param("publishedAt", value.publishedAt() == null
                        ? null : Timestamp.from(value.publishedAt()))
                .param("version", value.version())
                .update();
        return value;
    }

    @Override
    public List<TemplateData> findTemplates(UUID hospitalId) {
        return jdbc.sql("""
                select * from document_template_version
                where hospital_id=:hospitalId
                order by created_at desc,id
                """)
                .param("hospitalId", hospitalId)
                .query(this::mapTemplate)
                .list();
    }

    @Override
    public Optional<TemplateData> findTemplate(UUID hospitalId, UUID templateId) {
        return jdbc.sql("""
                select * from document_template_version
                where hospital_id=:hospitalId and id=:templateId
                """)
                .param("hospitalId", hospitalId)
                .param("templateId", templateId)
                .query(this::mapTemplate)
                .optional();
    }

    @Override
    @Transactional
    public Optional<TemplateData> publishTemplate(
            UUID hospitalId, UUID templateId, UUID publishedBy,
            Instant publishedAt, long expectedVersion) {
        TemplateData current = findTemplate(hospitalId, templateId).orElse(null);
        if (current == null || current.version() != expectedVersion
                || !"VALIDATED".equals(current.status())
                || !"VALID".equals(current.validationStatus())) return Optional.empty();
        jdbc.sql("""
                update document_template_version
                set status='ARCHIVED',version=version+1
                where hospital_id=:hospitalId and template_code=:templateCode
                  and status='PUBLISHED'
                """)
                .param("hospitalId", hospitalId)
                .param("templateCode", current.templateCode())
                .update();
        int updated = jdbc.sql("""
                update document_template_version
                set status='PUBLISHED',published_by=:publishedBy,
                    published_at=:publishedAt,version=version+1
                where hospital_id=:hospitalId and id=:templateId
                  and status='VALIDATED' and validation_status='VALID'
                  and version=:expectedVersion
                """)
                .param("publishedBy", publishedBy)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("hospitalId", hospitalId)
                .param("templateId", templateId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1 ? findTemplate(hospitalId, templateId) : Optional.empty();
    }

    @Override
    public Optional<ExportData> findExportByAgentTask(
            UUID hospitalId, UUID agentTaskId) {
        return jdbc.sql("""
                select * from document_export_record
                where hospital_id=:hospitalId and agent_task_id=:agentTaskId
                """)
                .param("hospitalId", hospitalId)
                .param("agentTaskId", agentTaskId)
                .query(this::mapExport)
                .optional();
    }

    @Override
    public Optional<ExportData> findExportById(UUID hospitalId, UUID exportId) {
        return jdbc.sql("""
                select * from document_export_record
                where hospital_id=:hospitalId and id=:exportId
                """)
                .param("hospitalId", hospitalId)
                .param("exportId", exportId)
                .query(this::mapExport)
                .optional();
    }

    @Override
    public ExportData createExport(ExportData value) {
        jdbc.sql("""
                insert into document_export_record(
                    id,hospital_id,project_id,agent_task_id,protocol_id,
                    review_task_id,template_version_id,citation_style_version_id,
                    citation_style_code,
                    citation_style_version,status,requested_by,confirmed_at,
                    protocol_snapshot_sha256,citation_snapshot_sha256,citation_count,
                    object_key,file_name,content_type,content_sha256,content_size,
                    completed_at,error_code,error_message,created_at,version
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:protocolId,
                    :reviewTaskId,:templateVersionId,:citationStyleVersionId,
                    :citationStyleCode,
                    :citationStyleVersion,:status,:requestedBy,:confirmedAt,
                    :protocolSnapshotSha256,:citationSnapshotSha256,:citationCount,
                    :objectKey,:fileName,:contentType,:contentSha256,:contentSize,
                    :completedAt,:errorCode,:errorMessage,:createdAt,:version
                )
                on conflict (agent_task_id) do nothing
                """)
                .param("id", value.id())
                .param("hospitalId", value.hospitalId())
                .param("projectId", value.projectId())
                .param("agentTaskId", value.agentTaskId())
                .param("protocolId", value.protocolId())
                .param("reviewTaskId", value.reviewTaskId())
                .param("templateVersionId", value.templateVersionId())
                .param("citationStyleVersionId", value.citationStyleVersionId())
                .param("citationStyleCode", value.citationStyleCode())
                .param("citationStyleVersion", value.citationStyleVersion())
                .param("status", value.status())
                .param("requestedBy", value.requestedBy())
                .param("confirmedAt", Timestamp.from(value.confirmedAt()))
                .param("protocolSnapshotSha256", value.protocolSnapshotSha256())
                .param("citationSnapshotSha256", value.citationSnapshotSha256())
                .param("citationCount", value.citationCount())
                .param("objectKey", value.objectKey())
                .param("fileName", value.fileName())
                .param("contentType", value.contentType())
                .param("contentSha256", value.contentSha256())
                .param("contentSize", value.contentSize())
                .param("completedAt", value.completedAt() == null
                        ? null : Timestamp.from(value.completedAt()))
                .param("errorCode", value.errorCode())
                .param("errorMessage", value.errorMessage())
                .param("createdAt", Timestamp.from(value.createdAt()))
                .param("version", value.version())
                .update();
        return findExportByAgentTask(value.hospitalId(), value.agentTaskId())
                .orElseThrow();
    }

    private TemplateData mapTemplate(ResultSet result, int row) throws SQLException {
        return new TemplateData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getString("template_code"),
                result.getString("template_name"),
                result.getInt("version_no"),
                result.getString("status"),
                result.getString("object_key"),
                result.getString("content_sha256"),
                result.getLong("content_size"),
                result.getString("placeholder_schema_version"),
                result.getString("placeholders_json"),
                result.getString("validation_status"),
                result.getString("validation_message"),
                result.getObject("created_by", UUID.class),
                result.getTimestamp("created_at").toInstant(),
                result.getObject("published_by", UUID.class),
                result.getTimestamp("published_at") == null ? null
                        : result.getTimestamp("published_at").toInstant(),
                result.getLong("version"));
    }

    private ExportData mapExport(ResultSet result, int row) throws SQLException {
        return new ExportData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("agent_task_id", UUID.class),
                result.getObject("protocol_id", UUID.class),
                result.getObject("review_task_id", UUID.class),
                result.getObject("template_version_id", UUID.class),
                result.getObject("citation_style_version_id", UUID.class),
                result.getString("citation_style_code"),
                result.getString("citation_style_version"),
                result.getString("status"),
                result.getObject("requested_by", UUID.class),
                result.getTimestamp("confirmed_at").toInstant(),
                result.getString("protocol_snapshot_sha256"),
                result.getString("citation_snapshot_sha256"),
                result.getInt("citation_count"),
                result.getString("object_key"),
                result.getString("file_name"),
                result.getString("content_type"),
                result.getString("content_sha256"),
                result.getLong("content_size"),
                result.getTimestamp("completed_at") == null ? null
                        : result.getTimestamp("completed_at").toInstant(),
                result.getString("error_code"),
                result.getString("error_message"),
                result.getTimestamp("created_at").toInstant(),
                result.getLong("version"));
    }
}
