package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.agent.model.ModelInvocation.ModelUsage;
import com.jarylee.medicalagent.agent.model.ModelRoute.Pricing;
import org.springframework.stereotype.Component;

@Component
public class ModelCostCalculator {
    private static final long TOKEN_PRICE_UNIT = 1_000_000L;

    public CostResult calculate(Pricing pricing, ModelUsage usage) {
        if (usage != null && "SYNTHETIC_TEST".equals(usage.source())) {
            return new CostResult("TEST_ONLY", null);
        }
        if (pricing == null || !pricing.priced()) {
            return new CostResult("UNPRICED", null);
        }
        if (usage == null || !"PROVIDER_REPORTED".equals(usage.source())
                || usage.inputTokens() == null || usage.outputTokens() == null) {
            return new CostResult("USAGE_UNAVAILABLE", null);
        }
        long cached = usage.cachedInputTokens() == null
                ? 0L : usage.cachedInputTokens();
        long uncached = Math.subtractExact(usage.inputTokens(), cached);
        long cachedRate = pricing.cachedInputMicrosPerMillion() == null
                ? pricing.inputMicrosPerMillion()
                : pricing.cachedInputMicrosPerMillion();
        long cost = Math.addExact(
                Math.addExact(
                        pricedTokens(uncached, pricing.inputMicrosPerMillion()),
                        pricedTokens(cached, cachedRate)),
                pricedTokens(usage.outputTokens(), pricing.outputMicrosPerMillion()));
        return new CostResult("ESTIMATED", cost);
    }

    public long estimateMaximum(
            Pricing pricing, long inputTokens, long outputTokens) {
        if (pricing == null || !pricing.priced()
                || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException(
                    "最坏成本估算需要版本化价格和非负 Token 上限");
        }
        return Math.addExact(
                pricedTokens(inputTokens, pricing.inputMicrosPerMillion()),
                pricedTokens(outputTokens, pricing.outputMicrosPerMillion()));
    }

    private long pricedTokens(long tokens, long microsPerMillion) {
        long numerator = Math.multiplyExact(tokens, microsPerMillion);
        if (numerator == 0) return 0;
        return Math.addExact(numerator, TOKEN_PRICE_UNIT - 1) / TOKEN_PRICE_UNIT;
    }

    public record CostResult(String status, Long estimatedCostMicros) {}
}
