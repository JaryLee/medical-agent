package com.jarylee.medicalagent.document;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentExportRepository {
    int nextTemplateVersion(UUID hospitalId, String templateCode);

    TemplateData createTemplate(TemplateData template);

    List<TemplateData> findTemplates(UUID hospitalId);

    Optional<TemplateData> findTemplate(UUID hospitalId, UUID templateId);

    Optional<TemplateData> publishTemplate(
            UUID hospitalId, UUID templateId, UUID publishedBy,
            Instant publishedAt, long expectedVersion);

    Optional<ExportData> findExportByAgentTask(UUID hospitalId, UUID agentTaskId);

    Optional<ExportData> findExportById(UUID hospitalId, UUID exportId);

    ExportData createExport(ExportData export);

    record TemplateData(
            UUID id,
            UUID hospitalId,
            String templateCode,
            String templateName,
            int versionNo,
            String status,
            String objectKey,
            String contentSha256,
            long contentSize,
            String placeholderSchemaVersion,
            String placeholdersJson,
            String validationStatus,
            String validationMessage,
            UUID createdBy,
            Instant createdAt,
            UUID publishedBy,
            Instant publishedAt,
            long version
    ) {}

    record ExportData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            UUID protocolId,
            UUID reviewTaskId,
            UUID templateVersionId,
            UUID citationStyleVersionId,
            String citationStyleCode,
            String citationStyleVersion,
            String status,
            UUID requestedBy,
            Instant confirmedAt,
            String protocolSnapshotSha256,
            String citationSnapshotSha256,
            int citationCount,
            String objectKey,
            String fileName,
            String contentType,
            String contentSha256,
            long contentSize,
            Instant completedAt,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            long version
    ) {}
}
