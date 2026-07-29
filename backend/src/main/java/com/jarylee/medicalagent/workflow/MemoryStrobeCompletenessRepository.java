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
public class MemoryStrobeCompletenessRepository
        implements StrobeCompletenessRepository {
    private final Map<UUID, CheckTaskData> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, List<StrobeCompletenessModels.CheckItem>> items =
            new ConcurrentHashMap<>();

    @Override
    public Optional<CheckTaskData> findByAgentTask(
            UUID hospitalId, UUID agentTaskId) {
        return tasks.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.agentTaskId().equals(agentTaskId))
                .findFirst();
    }

    @Override
    public void save(
            CheckTaskData task,
            List<StrobeCompletenessModels.CheckItem> values) {
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new IllegalStateException("STROBE 预检查任务已存在");
        }
        items.put(task.id(), List.copyOf(values));
    }

    List<CheckTaskData> all() {
        return List.copyOf(tasks.values());
    }

    List<StrobeCompletenessModels.CheckItem> items(UUID taskId) {
        return items.getOrDefault(taskId, List.of());
    }
}
