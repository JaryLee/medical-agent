package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchProtocolRepository {
    Optional<ProtocolData> findByAgentTask(UUID hospitalId, UUID agentTaskId);

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
}
