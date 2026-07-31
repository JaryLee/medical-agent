package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchProtocolRepository {
    Optional<ProtocolData> findByAgentTask(UUID hospitalId, UUID agentTaskId);

    List<SectionData> findSections(UUID hospitalId, UUID protocolId);

    List<SectionVersionData> findSectionVersions(
            UUID hospitalId, UUID sectionId);

    List<ProjectSectionVersionData> findProjectSectionVersions(
            UUID hospitalId, UUID projectId, String sectionCode);

    Optional<SectionVersionData> appendSectionVersion(
            UUID hospitalId,
            UUID protocolId,
            UUID sectionId,
            int expectedVersionNo,
            String content,
            String origin,
            String changeReason,
            UUID createdBy,
            Instant createdAt);

    boolean updateResultSnapshot(
            UUID hospitalId, UUID protocolId, String resultJson, Instant updatedAt);

    void save(
            ProtocolData protocol,
            List<ResearchProtocolModels.ProtocolSection> sections);

    record ProtocolData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            String status,
            String studyType,
            String title,
            String schemaVersion,
            String generatorVersion,
            String inputSha256,
            String issuesToConfirmJson,
            String resultJson,
            Instant createdAt
    ) {}

    record SectionData(
            UUID id,
            UUID hospitalId,
            UUID protocolId,
            String sectionCode,
            String title,
            int sortOrder,
            int currentVersionNo,
            String status) {}

    record SectionVersionData(
            UUID id,
            UUID hospitalId,
            UUID sectionId,
            int versionNo,
            String content,
            String contentFormat,
            String origin,
            String evidenceStatus,
            String sourceIdentifiersJson,
            String issuesToConfirmJson,
            String changeReason,
            UUID createdBy,
            Instant createdAt) {}

    record ProjectSectionVersionData(
            String sectionCode,
            String title,
            Instant protocolCreatedAt,
            int sourceVersionNo,
            String content,
            String contentFormat,
            String origin,
            String evidenceStatus,
            String changeReason,
            Instant createdAt) {}
}
