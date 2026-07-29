package com.jarylee.medicalagent.literature;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemorySimilarResearchAnalysisRepository
        implements SimilarResearchAnalysisRepository {
    private final Map<UUID, AnalysisData> analyses = new ConcurrentHashMap<>();

    @Override
    public void create(AnalysisData analysis) {
        if (analyses.putIfAbsent(analysis.id(), analysis) != null) {
            throw new IllegalStateException("相似研究分析任务已存在");
        }
    }

    @Override
    public void complete(
            AnalysisData analysis,
            List<SimilarResearchAnalysisModels.SimilarResearch> comparisons,
            List<SimilarResearchAnalysisModels.ResearchGap> gaps) {
        analyses.compute(analysis.id(), (id, current) -> {
            if (current == null || !"RUNNING".equals(current.status())) {
                throw new IllegalStateException("相似研究分析任务当前不可完成");
            }
            return analysis;
        });
    }

    @Override
    public void fail(
            UUID hospitalId, UUID analysisId, String errorCode,
            String errorMessage, Instant completedAt) {
        analyses.computeIfPresent(analysisId, (id, current) -> new AnalysisData(
                current.id(), current.hospitalId(), current.projectId(),
                current.agentTaskId(), "FAILED", current.startedAt(), completedAt,
                null, null, null, null, null, null, current.inputSha256(),
                current.algorithmVersion(), current.databaseScopeJson(), null,
                null, errorCode, truncate(errorMessage)));
    }

    List<AnalysisData> all() {
        return List.copyOf(analyses.values());
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
