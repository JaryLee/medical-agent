package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@Profile("memory")
public class MemoryModelCallAuditRepository implements ModelCallAuditRepository {
    private final Map<UUID, ModelCallData> calls = new LinkedHashMap<>();

    @Override
    public synchronized void start(ModelCallData call) {
        if (calls.putIfAbsent(call.id(), call) != null) {
            throw new IllegalStateException("模型调用审计ID重复");
        }
    }

    @Override
    public synchronized void succeed(
            UUID callId, CompletionData completion, Instant completedAt) {
        update(callId, "SUCCEEDED", completion, null, null, completedAt);
    }

    @Override
    public synchronized void fail(
            UUID callId, String errorCode, String errorMessage,
            Instant completedAt) {
        update(callId, "FAILED", null, errorCode, errorMessage, completedAt);
    }

    @Override
    public synchronized List<ModelCallData> findByTask(UUID hospitalId, UUID taskId) {
        return calls.values().stream()
                .filter(call -> call.hospitalId().equals(hospitalId)
                        && call.taskId().equals(taskId))
                .toList();
    }

    @Override
    public synchronized List<ModelCallData> findByProject(
            UUID hospitalId, UUID projectId) {
        return calls.values().stream()
                .filter(call -> call.hospitalId().equals(hospitalId)
                        && call.projectId().equals(projectId))
                .sorted(java.util.Comparator.comparing(
                        ModelCallData::startedAt).reversed())
                .toList();
    }

    @Override
    public synchronized ProjectConsumption projectConsumption(
            UUID hospitalId, UUID projectId) {
        long committed = 0;
        long active = 0;
        long succeeded = 0;
        long count = 0;
        for (ModelCallData call : findByProject(hospitalId, projectId)) {
            count++;
            long reservation = call.reservedCostMicros() == null
                    ? 0 : call.reservedCostMicros();
            if ("REQUESTED".equals(call.status())) {
                active = Math.addExact(active, reservation);
                committed = Math.addExact(committed, reservation);
            } else if ("FAILED".equals(call.status())) {
                committed = Math.addExact(committed, reservation);
            } else if ("SUCCEEDED".equals(call.status())) {
                long actual = call.estimatedCostMicros() == null
                        ? reservation : call.estimatedCostMicros();
                succeeded = Math.addExact(succeeded, actual);
                committed = Math.addExact(committed, actual);
            }
        }
        return new ProjectConsumption(committed, active, succeeded, count);
    }

    @Override
    public synchronized int purgeExpiredPayloadSnapshots(Instant now, int limit) {
        int purged = 0;
        for (var entry : new ArrayList<>(calls.entrySet())) {
            if (purged >= limit) {
                break;
            }
            ModelCallData call = entry.getValue();
            if (call.payloadRetentionUntil() == null
                    || call.payloadRetentionUntil().isAfter(now)
                    || call.payloadPurgedAt() != null
                    || call.rawPayloadObjectKey() != null
                    || "REQUESTED".equals(call.status())) {
                continue;
            }
            calls.put(entry.getKey(), copyWithPayloadPurged(call, now));
            purged++;
        }
        return purged;
    }

    @Override
    public synchronized int purgeExpiredMetadata(Instant now, int limit) {
        var expiredIds = calls.values().stream()
                .filter(call -> !call.metadataRetentionUntil().isAfter(now))
                .filter(call -> call.rawPayloadObjectKey() == null)
                .filter(call -> !"REQUESTED".equals(call.status()))
                .limit(limit)
                .map(ModelCallData::id)
                .toList();
        expiredIds.forEach(calls::remove);
        return expiredIds.size();
    }

    @Override
    public synchronized long countExpiredObjectPayloads(Instant now) {
        return calls.values().stream()
                .filter(call -> call.payloadRetentionUntil() != null)
                .filter(call -> !call.payloadRetentionUntil().isAfter(now))
                .filter(call -> call.payloadPurgedAt() == null)
                .filter(call -> call.rawPayloadObjectKey() != null)
                .filter(call -> !"REQUESTED".equals(call.status()))
                .count();
    }

    private void update(
            UUID callId, String status, CompletionData completion,
            String errorCode, String errorMessage,
            Instant completedAt) {
        ModelCallData call = calls.get(callId);
        if (call == null || !"REQUESTED".equals(call.status())) {
            throw new IllegalStateException("模型调用审计状态不可更新");
        }
        calls.put(callId, new ModelCallData(
                call.id(), call.hospitalId(), call.projectId(), call.taskId(),
                call.stepCode(), call.attemptNo(), call.provider(), call.modelName(),
                call.promptVersion(), call.inputSchemaVersion(),
                call.outputSchemaVersion(), call.inputSha256(),
                completion == null ? null : completion.outputSha256(),
                call.inputSnapshotJson(),
                completion == null ? null : completion.outputSnapshotJson(),
                call.rawPayloadObjectKey(), call.payloadPurgedAt(),
                status, errorCode, errorMessage,
                call.startedAt(), completedAt, call.payloadRetentionUntil(),
                call.metadataRetentionUntil(),
                call.logicalModelType(), call.routePolicyVersion(), call.routeReason(),
                completion == null ? call.providerRequestId()
                        : completion.providerRequestId(),
                completion == null ? call.usageSource()
                        : completion.usageSource(),
                completion == null ? call.inputTokens()
                        : completion.inputTokens(),
                completion == null ? call.cachedInputTokens()
                        : completion.cachedInputTokens(),
                completion == null ? call.outputTokens()
                        : completion.outputTokens(),
                completion == null ? call.totalTokens()
                        : completion.totalTokens(),
                call.priceVersion(), call.priceCurrency(),
                completion == null ? call.estimatedCostMicros()
                        : completion.estimatedCostMicros(),
                completion == null ? call.costStatus()
                        : completion.costStatus(),
                call.reservedCostMicros()));
    }

    private ModelCallData copyWithPayloadPurged(ModelCallData call, Instant purgedAt) {
        return new ModelCallData(
                call.id(), call.hospitalId(), call.projectId(), call.taskId(),
                call.stepCode(), call.attemptNo(), call.provider(), call.modelName(),
                call.promptVersion(), call.inputSchemaVersion(),
                call.outputSchemaVersion(), call.inputSha256(), call.outputSha256(),
                null, null, null, purgedAt, call.status(), call.errorCode(),
                call.errorMessage(), call.startedAt(), call.completedAt(),
                call.payloadRetentionUntil(), call.metadataRetentionUntil(),
                call.logicalModelType(), call.routePolicyVersion(), call.routeReason(),
                call.providerRequestId(), call.usageSource(), call.inputTokens(),
                call.cachedInputTokens(), call.outputTokens(), call.totalTokens(),
                call.priceVersion(), call.priceCurrency(),
                call.estimatedCostMicros(), call.costStatus(),
                call.reservedCostMicros());
    }
}
