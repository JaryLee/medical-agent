package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ModelCallAuditRepository {
    void start(ModelCallData call);

    void succeed(
            UUID callId, CompletionData completion, Instant completedAt);

    default void succeed(
            UUID callId, String outputSha256, String outputSnapshotJson,
            Instant completedAt) {
        succeed(
                callId,
                new CompletionData(
                        outputSha256, outputSnapshotJson, null,
                        "NOT_AVAILABLE", null, null, null, null,
                        null, "UNPRICED"),
                completedAt);
    }

    void fail(
            UUID callId, String errorCode, String errorMessage,
            Instant completedAt);

    List<ModelCallData> findByTask(UUID hospitalId, UUID taskId);

    List<ModelCallData> findByProject(UUID hospitalId, UUID projectId);

    ProjectConsumption projectConsumption(
            UUID hospitalId, UUID projectId);

    int purgeExpiredPayloadSnapshots(Instant now, int limit);

    int purgeExpiredMetadata(Instant now, int limit);

    long countExpiredObjectPayloads(Instant now);

    record ModelCallData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID taskId,
            String stepCode,
            int attemptNo,
            String provider,
            String modelName,
            String promptVersion,
            String inputSchemaVersion,
            String outputSchemaVersion,
            String inputSha256,
            String outputSha256,
            String inputSnapshotJson,
            String outputSnapshotJson,
            String rawPayloadObjectKey,
            Instant payloadPurgedAt,
            String status,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant completedAt,
            Instant payloadRetentionUntil,
            Instant metadataRetentionUntil,
            String logicalModelType,
            String routePolicyVersion,
            String routeReason,
            String providerRequestId,
            String usageSource,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long totalTokens,
            String priceVersion,
            String priceCurrency,
            Long estimatedCostMicros,
            String costStatus,
            Long reservedCostMicros) {
        public ModelCallData(
                UUID id,
                UUID hospitalId,
                UUID projectId,
                UUID taskId,
                String stepCode,
                int attemptNo,
                String provider,
                String modelName,
                String promptVersion,
                String inputSchemaVersion,
                String outputSchemaVersion,
                String inputSha256,
                String outputSha256,
                String inputSnapshotJson,
                String outputSnapshotJson,
                String rawPayloadObjectKey,
                Instant payloadPurgedAt,
                String status,
                String errorCode,
                String errorMessage,
                Instant startedAt,
                Instant completedAt,
                Instant payloadRetentionUntil,
                Instant metadataRetentionUntil) {
            this(
                    id, hospitalId, projectId, taskId, stepCode, attemptNo,
                    provider, modelName, promptVersion, inputSchemaVersion,
                    outputSchemaVersion, inputSha256, outputSha256,
                    inputSnapshotJson, outputSnapshotJson, rawPayloadObjectKey,
                    payloadPurgedAt, status, errorCode, errorMessage,
                    startedAt, completedAt, payloadRetentionUntil,
                    metadataRetentionUntil, "RESEARCH_FAST",
                    "legacy-single-route/v1", "LEGACY_OR_DEFAULT", null,
                    "NOT_AVAILABLE", null, null, null, null,
                    null, null, null, "UNPRICED", null);
        }
    }

    record CompletionData(
            String outputSha256,
            String outputSnapshotJson,
            String providerRequestId,
            String usageSource,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long totalTokens,
            Long estimatedCostMicros,
            String costStatus) {}

    record ProjectConsumption(
            long committedOrReservedCostMicros,
            long activeReservationCostMicros,
            long succeededCostMicros,
            long callCount) {}
}
