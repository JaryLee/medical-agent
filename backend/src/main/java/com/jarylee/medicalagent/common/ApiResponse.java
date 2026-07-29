package com.jarylee.medicalagent.common;

import java.time.Instant;

public record ApiResponse<T>(boolean success, T data, ApiError error, Instant timestamp, String traceId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now(), currentTraceId());
    }

    public static <T> ApiResponse<T> failure(String code, String message, String traceId) {
        return new ApiResponse<>(false, null, new ApiError(code, message, traceId), Instant.now(), traceId);
    }

    public record ApiError(String code, String message, String traceId) {}

    private static String currentTraceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId == null ? "not-request-context" : traceId;
    }
}
