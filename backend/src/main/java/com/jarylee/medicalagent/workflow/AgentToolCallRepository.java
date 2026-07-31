package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AgentToolCallRepository {
    BeginOutcome begin(ToolCallData call);
    ToolCallData complete(
            UUID hospitalId, UUID taskId, UUID callId, String operationKey,
            String resultJson, Instant completedAt);
    void fail(
            UUID hospitalId, UUID taskId, UUID callId,
            String errorCode, String errorMessage, Instant completedAt);

    record ToolCallData(
            UUID id, UUID hospitalId, UUID taskId, UUID stepAttemptId,
            String stepCode, int attemptNo, String toolCallKey,
            String operationKey, String requestSha256, String status,
            String resultJson, String errorCode, String errorMessage,
            Instant startedAt, Instant completedAt) {}

    record BeginOutcome(boolean acquired, ToolCallData call) {}
}
