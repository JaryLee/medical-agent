package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("memory")
public class MemoryProjectModelBudgetRepository
        implements ProjectModelBudgetRepository {
    private final Map<String, BudgetData> budgets = new LinkedHashMap<>();

    @Override
    public synchronized BudgetData lockOrCreate(
            UUID hospitalId, UUID projectId, UUID createdBy,
            String currency, long defaultMaxCallCostMicros,
            long defaultMaxProjectCostMicros, Instant now) {
        return budgets.computeIfAbsent(key(hospitalId, projectId), ignored ->
                new BudgetData(
                        UUID.randomUUID(), hospitalId, projectId, currency,
                        defaultMaxCallCostMicros,
                        defaultMaxProjectCostMicros,
                        "ACTIVE", createdBy, now, now, 0));
    }

    @Override
    public synchronized Optional<BudgetData> find(
            UUID hospitalId, UUID projectId) {
        return Optional.ofNullable(budgets.get(key(hospitalId, projectId)));
    }

    @Override
    public synchronized BudgetData update(
            UUID hospitalId, UUID projectId, long expectedVersion,
            long maxCallCostMicros, long maxProjectCostMicros,
            String status, Instant updatedAt) {
        String key = key(hospitalId, projectId);
        BudgetData current = budgets.get(key);
        if (current == null || current.version() != expectedVersion) {
            throw new IllegalStateException("模型预算版本冲突");
        }
        BudgetData updated = new BudgetData(
                current.id(), current.hospitalId(), current.projectId(),
                current.currency(), maxCallCostMicros,
                maxProjectCostMicros, status, current.createdBy(),
                current.createdAt(), updatedAt, current.version() + 1);
        budgets.put(key, updated);
        return updated;
    }

    private String key(UUID hospitalId, UUID projectId) {
        return hospitalId + ":" + projectId;
    }
}
