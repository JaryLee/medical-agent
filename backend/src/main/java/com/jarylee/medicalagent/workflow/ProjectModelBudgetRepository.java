package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectModelBudgetRepository {
    BudgetData lockOrCreate(
            UUID hospitalId,
            UUID projectId,
            UUID createdBy,
            String currency,
            long defaultMaxCallCostMicros,
            long defaultMaxProjectCostMicros,
            Instant now);

    Optional<BudgetData> find(UUID hospitalId, UUID projectId);

    BudgetData update(
            UUID hospitalId,
            UUID projectId,
            long expectedVersion,
            long maxCallCostMicros,
            long maxProjectCostMicros,
            String status,
            Instant updatedAt);

    record BudgetData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            String currency,
            long maxCallCostMicros,
            long maxProjectCostMicros,
            String status,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            long version) {}
}
