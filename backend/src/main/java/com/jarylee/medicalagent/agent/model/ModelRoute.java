package com.jarylee.medicalagent.agent.model;

import java.util.Locale;
import java.util.Objects;

public record ModelRoute(
        LogicalModelType logicalModelType,
        ResearchModel model,
        String policyVersion,
        String routeReason,
        Pricing pricing
) {
    public ModelRoute {
        Objects.requireNonNull(logicalModelType, "逻辑模型类型不能为空");
        Objects.requireNonNull(model, "路由模型不能为空");
        policyVersion = requireText(policyVersion, "路由策略版本不能为空");
        routeReason = requireText(routeReason, "路由原因不能为空");
        pricing = pricing == null ? Pricing.unpriced() : pricing;
    }

    public String identity() {
        return model.provider().strip().toLowerCase(Locale.ROOT)
                + "/" + model.modelName().strip().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }

    public record Pricing(
            String version,
            String currency,
            Long inputMicrosPerMillion,
            Long cachedInputMicrosPerMillion,
            Long outputMicrosPerMillion
    ) {
        public Pricing {
            if (version != null && version.isBlank()) version = null;
            if (currency != null) currency = currency.strip().toUpperCase(Locale.ROOT);
            requireNonNegative(inputMicrosPerMillion);
            requireNonNegative(cachedInputMicrosPerMillion);
            requireNonNegative(outputMicrosPerMillion);
            boolean any = inputMicrosPerMillion != null
                    || cachedInputMicrosPerMillion != null
                    || outputMicrosPerMillion != null;
            if (any && (version == null || currency == null
                    || !currency.matches("[A-Z]{3}")
                    || inputMicrosPerMillion == null
                    || outputMicrosPerMillion == null)) {
                throw new IllegalArgumentException("模型计价必须包含版本、币种、输入价和输出价");
            }
        }

        public static Pricing unpriced() {
            return new Pricing(null, null, null, null, null);
        }

        public boolean priced() {
            return version != null;
        }

        private static void requireNonNegative(Long value) {
            if (value != null && value < 0) {
                throw new IllegalArgumentException("模型 Token 单价不能为负数");
            }
        }
    }
}
