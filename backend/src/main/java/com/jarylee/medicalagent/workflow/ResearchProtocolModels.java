package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ResearchProtocolModels {
    private ResearchProtocolModels() {}

    public record ProtocolSection(
            UUID sectionId,
            String sectionCode,
            String title,
            int sortOrder,
            int versionNo,
            String content,
            String contentFormat,
            String origin,
            String evidenceStatus,
            List<String> sourceIdentifiers,
            List<String> issuesToConfirm
    ) {}

    public record ProtocolDraft(
            String schemaVersion,
            UUID protocolId,
            Instant generatedAt,
            StudyType studyType,
            String title,
            List<ProtocolSection> sections,
            List<String> issuesToConfirm,
            String inputSha256,
            String generatorVersion,
            List<String> limitations
    ) {}
}
