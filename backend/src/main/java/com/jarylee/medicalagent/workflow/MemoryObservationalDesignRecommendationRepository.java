package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryObservationalDesignRecommendationRepository
        implements ObservationalDesignRecommendationRepository {
    private final Map<UUID, RecommendationData> recommendations =
            new ConcurrentHashMap<>();

    @Override
    public void create(RecommendationData recommendation) {
        if (recommendations.putIfAbsent(recommendation.id(), recommendation) != null) {
            throw new IllegalStateException("观察性研究设计推荐任务已存在");
        }
    }

    @Override
    public void complete(
            RecommendationData recommendation,
            List<ObservationalDesignRecommendationModels.DesignAlternative> alternatives) {
        recommendations.compute(recommendation.id(), (id, current) -> {
            if (current == null || !"RUNNING".equals(current.status())) {
                throw new IllegalStateException("观察性研究设计推荐任务当前不可完成");
            }
            return recommendation;
        });
    }

    @Override
    public void fail(
            UUID hospitalId, UUID recommendationId, String errorCode,
            String errorMessage, Instant completedAt) {
        recommendations.computeIfPresent(recommendationId, (id, current) ->
                new RecommendationData(
                        current.id(), current.hospitalId(), current.projectId(),
                        current.agentTaskId(), "FAILED", current.startedAt(), completedAt,
                        null, null, null, null, null, null,
                        current.inputSha256(), current.algorithmVersion(), null,
                        errorCode, truncate(errorMessage)));
    }

    List<RecommendationData> all() {
        return List.copyOf(recommendations.values());
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
