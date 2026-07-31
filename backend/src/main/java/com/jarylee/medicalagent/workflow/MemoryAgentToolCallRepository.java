package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
@Profile("memory")
public class MemoryAgentToolCallRepository implements AgentToolCallRepository {
    private final Map<UUID, ToolCallData> calls = new LinkedHashMap<>();

    @Override
    public synchronized BeginOutcome begin(ToolCallData call) {
        ToolCallData completed = completed(call);
        if (completed != null) return new BeginOutcome(false, completed);
        ToolCallData running = calls.values().stream()
                .filter(value -> value.hospitalId().equals(call.hospitalId())
                        && value.taskId().equals(call.taskId())
                        && value.stepCode().equals(call.stepCode())
                        && value.operationKey().equals(call.operationKey())
                        && "RUNNING".equals(value.status()))
                .findFirst().orElse(null);
        if (running != null) {
            throw new IllegalStateException(
                    "相同Agent工具操作仍在执行，拒绝重复调用");
        }
        ToolCallData sameAttempt = calls.values().stream()
                .filter(value -> value.hospitalId().equals(call.hospitalId())
                        && value.taskId().equals(call.taskId())
                        && value.stepCode().equals(call.stepCode())
                        && value.attemptNo() == call.attemptNo()
                        && value.toolCallKey().equals(call.toolCallKey()))
                .findFirst().orElse(null);
        if (sameAttempt != null) {
            if ("COMPLETED".equals(sameAttempt.status())) {
                return new BeginOutcome(false, sameAttempt);
            }
            throw new IllegalStateException("同一步骤尝试的工具调用已存在");
        }
        calls.put(call.id(), call);
        return new BeginOutcome(true, call);
    }

    @Override
    public synchronized ToolCallData complete(
            UUID hospitalId, UUID taskId, UUID callId, String operationKey,
            String resultJson, Instant completedAt) {
        ToolCallData current = require(hospitalId, taskId, callId);
        ToolCallData completed = completed(current);
        if (completed != null && !completed.id().equals(callId)) {
            calls.put(callId, copy(
                    current, "SUPERSEDED", null, null, null, completedAt));
            return completed;
        }
        ToolCallData updated = copy(
                current, "COMPLETED", resultJson, null, null, completedAt);
        calls.put(callId, updated);
        return updated;
    }

    @Override
    public synchronized void fail(
            UUID hospitalId, UUID taskId, UUID callId,
            String errorCode, String errorMessage, Instant completedAt) {
        ToolCallData current = require(hospitalId, taskId, callId);
        calls.put(callId, copy(
                current, "FAILED", null, errorCode,
                truncate(errorMessage), completedAt));
    }

    private ToolCallData completed(ToolCallData call) {
        return calls.values().stream()
                .filter(value -> value.hospitalId().equals(call.hospitalId())
                        && value.taskId().equals(call.taskId())
                        && value.stepCode().equals(call.stepCode())
                        && value.operationKey().equals(call.operationKey())
                        && "COMPLETED".equals(value.status()))
                .findFirst().orElse(null);
    }

    private ToolCallData require(UUID hospitalId, UUID taskId, UUID callId) {
        ToolCallData value = calls.get(callId);
        if (value == null || !value.hospitalId().equals(hospitalId)
                || !value.taskId().equals(taskId)) {
            throw new IllegalStateException("Agent工具调用不存在");
        }
        return value;
    }

    private ToolCallData copy(
            ToolCallData source, String status, String resultJson,
            String errorCode, String errorMessage, Instant completedAt) {
        return new ToolCallData(
                source.id(), source.hospitalId(), source.taskId(),
                source.stepAttemptId(), source.stepCode(), source.attemptNo(),
                source.toolCallKey(), source.operationKey(), source.requestSha256(),
                status, resultJson, errorCode, errorMessage,
                source.startedAt(), completedAt);
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
