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
public class MemoryClaimCitationValidationRepository
        implements ClaimCitationValidationRepository {
    private final Map<UUID, ValidationTaskData> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, List<ClaimCitationValidationModels.ResearchClaim>> claims =
            new ConcurrentHashMap<>();

    @Override
    public Optional<ValidationTaskData> findByAgentTask(
            UUID hospitalId, UUID agentTaskId) {
        return tasks.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.agentTaskId().equals(agentTaskId))
                .findFirst();
    }

    @Override
    public void save(
            ValidationTaskData task,
            List<ClaimCitationValidationModels.ResearchClaim> values) {
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new IllegalStateException("主张与引用验证任务已存在");
        }
        claims.put(task.id(), List.copyOf(values));
    }

    List<ValidationTaskData> all() {
        return List.copyOf(tasks.values());
    }

    List<ClaimCitationValidationModels.ResearchClaim> claims(UUID taskId) {
        return claims.getOrDefault(taskId, List.of());
    }
}
