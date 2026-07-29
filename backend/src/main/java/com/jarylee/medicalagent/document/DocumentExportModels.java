package com.jarylee.medicalagent.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DocumentExportModels {
    private DocumentExportModels() {}

    public record TemplateView(
            UUID id,
            String templateCode,
            String templateName,
            int versionNo,
            String status,
            String contentSha256,
            long contentSize,
            String placeholderSchemaVersion,
            List<String> placeholders,
            String validationStatus,
            String validationMessage,
            UUID createdBy,
            Instant createdAt,
            UUID publishedBy,
            Instant publishedAt,
            long version
    ) {}

    public record ExportView(
            UUID id,
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
            String fileName,
            String contentType,
            String contentSha256,
            long contentSize,
            Instant completedAt
    ) {}
}
