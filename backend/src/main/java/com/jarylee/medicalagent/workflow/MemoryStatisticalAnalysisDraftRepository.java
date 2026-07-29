package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryStatisticalAnalysisDraftRepository
        implements StatisticalAnalysisDraftRepository {
    private final Map<UUID, DraftData> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, List<StatisticalAnalysisModels.SampleSizeParameter>> parameters =
            new ConcurrentHashMap<>();

    @Override
    public Optional<DraftData> findByAgentTask(UUID hospitalId, UUID agentTaskId) {
        return drafts.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.agentTaskId().equals(agentTaskId))
                .findFirst();
    }

    @Override
    public void save(
            DraftData draft,
            List<StatisticalAnalysisModels.SampleSizeParameter> parameterValues,
            ResearchProtocolModels.ProtocolSection statisticalSection) {
        if (drafts.putIfAbsent(draft.id(), draft) != null) {
            throw new IllegalStateException("统计分析草案已存在");
        }
        parameters.put(draft.id(), List.copyOf(parameterValues));
    }

    List<DraftData> all() {
        return List.copyOf(drafts.values());
    }

    List<StatisticalAnalysisModels.SampleSizeParameter> parameters(UUID draftId) {
        return parameters.getOrDefault(draftId, List.of());
    }
}
