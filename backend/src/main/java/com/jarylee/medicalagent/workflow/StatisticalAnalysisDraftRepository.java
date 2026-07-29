package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatisticalAnalysisDraftRepository {
    Optional<DraftData> findByAgentTask(UUID hospitalId, UUID agentTaskId);

    void save(
            DraftData draft,
            List<StatisticalAnalysisModels.SampleSizeParameter> parameters,
            ResearchProtocolModels.ProtocolSection statisticalSection);

    record DraftData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            UUID protocolId,
            String status,
            String studyType,
            String primaryOutcome,
            String outcomeTypeStatus,
            int parameterCount,
            String inputSha256,
            String generatorVersion,
            String resultJson,
            Instant createdAt
    ) {}
}
