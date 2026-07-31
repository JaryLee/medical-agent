package com.jarylee.medicalagent.agent.model;

import java.util.Objects;

public record ModelInvocation<T>(
        T output,
        String providerRequestId,
        String finishReason,
        ModelUsage usage
) {
    public ModelInvocation {
        Objects.requireNonNull(output, "模型输出不能为空");
        finishReason = finishReason == null || finishReason.isBlank()
                ? "NOT_AVAILABLE" : finishReason;
        usage = usage == null ? ModelUsage.notAvailable() : usage;
    }

    public static <T> ModelInvocation<T> unmetered(T output) {
        return new ModelInvocation<>(output, null, "NOT_AVAILABLE", ModelUsage.notAvailable());
    }

    public record ModelUsage(
            String source,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long totalTokens
    ) {
        public ModelUsage {
            source = source == null || source.isBlank() ? "NOT_AVAILABLE" : source;
            requireNonNegative(inputTokens, "输入 Token");
            requireNonNegative(cachedInputTokens, "缓存输入 Token");
            requireNonNegative(outputTokens, "输出 Token");
            requireNonNegative(totalTokens, "总 Token");
            if (cachedInputTokens != null && inputTokens != null
                    && cachedInputTokens > inputTokens) {
                throw new IllegalArgumentException("缓存输入 Token 不能大于输入 Token");
            }
            if (totalTokens != null && inputTokens != null && outputTokens != null
                    && totalTokens < Math.addExact(inputTokens, outputTokens)) {
                throw new IllegalArgumentException("总 Token 不能小于输入与输出 Token 之和");
            }
        }

        public static ModelUsage notAvailable() {
            return new ModelUsage("NOT_AVAILABLE", null, null, null, null);
        }

        public static ModelUsage providerReported(
                long inputTokens, long cachedInputTokens,
                long outputTokens, long totalTokens) {
            return new ModelUsage(
                    "PROVIDER_REPORTED", inputTokens, cachedInputTokens,
                    outputTokens, totalTokens);
        }

        private static void requireNonNegative(Long value, String label) {
            if (value != null && value < 0) {
                throw new IllegalArgumentException(label + "不能为负数");
            }
        }
    }
}
