package com.jarylee.medicalagent.document;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryDocumentExportRepository implements DocumentExportRepository {
    private final Map<UUID, TemplateData> templates = new ConcurrentHashMap<>();
    private final Map<UUID, ExportData> exports = new ConcurrentHashMap<>();

    @Override
    public int nextTemplateVersion(UUID hospitalId, String templateCode) {
        return templates.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.templateCode().equals(templateCode))
                .mapToInt(TemplateData::versionNo).max().orElse(0) + 1;
    }

    @Override
    public TemplateData createTemplate(TemplateData template) {
        templates.put(template.id(), template);
        return template;
    }

    @Override
    public List<TemplateData> findTemplates(UUID hospitalId) {
        return templates.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId))
                .sorted(Comparator.comparing(TemplateData::createdAt).reversed())
                .toList();
    }

    @Override
    public Optional<TemplateData> findTemplate(UUID hospitalId, UUID templateId) {
        return Optional.ofNullable(templates.get(templateId))
                .filter(value -> value.hospitalId().equals(hospitalId));
    }

    @Override
    public synchronized Optional<TemplateData> publishTemplate(
            UUID hospitalId, UUID templateId, UUID publishedBy,
            Instant publishedAt, long expectedVersion) {
        TemplateData current = templates.get(templateId);
        if (current == null || !current.hospitalId().equals(hospitalId)
                || current.version() != expectedVersion
                || !"VALIDATED".equals(current.status())
                || !"VALID".equals(current.validationStatus())) return Optional.empty();
        templates.replaceAll((id, value) -> value.hospitalId().equals(hospitalId)
                && value.templateCode().equals(current.templateCode())
                && "PUBLISHED".equals(value.status())
                ? copyStatus(value, "ARCHIVED", value.publishedBy(), value.publishedAt())
                : value);
        TemplateData published = copyStatus(
                current, "PUBLISHED", publishedBy, publishedAt);
        templates.put(templateId, published);
        return Optional.of(published);
    }

    @Override
    public Optional<ExportData> findExportByAgentTask(UUID hospitalId, UUID agentTaskId) {
        return exports.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.agentTaskId().equals(agentTaskId))
                .findFirst();
    }

    @Override
    public Optional<ExportData> findExportById(UUID hospitalId, UUID exportId) {
        return Optional.ofNullable(exports.get(exportId))
                .filter(value -> value.hospitalId().equals(hospitalId));
    }

    @Override
    public synchronized ExportData createExport(ExportData export) {
        Optional<ExportData> existing =
                findExportByAgentTask(export.hospitalId(), export.agentTaskId());
        if (existing.isPresent()) return existing.get();
        exports.put(export.id(), export);
        return export;
    }

    private TemplateData copyStatus(
            TemplateData source, String status, UUID publishedBy, Instant publishedAt) {
        return new TemplateData(
                source.id(), source.hospitalId(), source.templateCode(),
                source.templateName(), source.versionNo(), status, source.objectKey(),
                source.contentSha256(), source.contentSize(),
                source.placeholderSchemaVersion(), source.placeholdersJson(),
                source.validationStatus(), source.validationMessage(),
                source.createdBy(), source.createdAt(), publishedBy, publishedAt,
                source.version() + 1);
    }
}
