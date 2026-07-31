package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AgentToolCallService {
    private final AgentToolCallRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public AgentToolCallService(
            AgentToolCallRepository repository, ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    public <T> T invoke(
            AgentWorkflowRepository.ClaimHandle claim,
            String toolCallKey, Object request, Class<T> resultType,
            Supplier<T> operation) {
        String requestJson = write(request);
        String requestHash = sha256(requestJson);
        String operationKey = sha256(
                claim.taskId() + "|" + claim.stepCode() + "|"
                        + toolCallKey + "|" + requestHash);
        var call = new AgentToolCallRepository.ToolCallData(
                UUID.randomUUID(), claim.hospitalId(), claim.taskId(),
                claim.stepAttemptId(), claim.stepCode(), claim.attemptNo(),
                toolCallKey, operationKey, requestHash, "RUNNING", null,
                null, null, clock.instant(), null);
        var begun = repository.begin(call);
        if (!begun.acquired()) {
            return read(begun.call().resultJson(), resultType);
        }
        try {
            T result = operation.get();
            var completed = repository.complete(
                    claim.hospitalId(), claim.taskId(), call.id(), operationKey,
                    write(result), clock.instant());
            return read(completed.resultJson(), resultType);
        } catch (RuntimeException exception) {
            repository.fail(
                    claim.hospitalId(), claim.taskId(), call.id(),
                    "TOOL_CALL_FAILED", exception.getMessage(), clock.instant());
            throw exception;
        }
    }

    private String write(Object value) {
        try {
            return json.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent工具调用序列化失败", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent工具调用缓存结果损坏", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Agent工具调用操作键生成失败", exception);
        }
    }
}
