package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.agent.model.ModelInvocation.ModelUsage;
import com.jarylee.medicalagent.agent.model.ModelRoute.Pricing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCostCalculatorTest {
    private final ModelCostCalculator calculator = new ModelCostCalculator();

    @Test
    void calculatesCachedAndUncachedProviderUsageWithCeilingMicros() {
        Pricing pricing = new Pricing(
                "provider-price/v1", "usd",
                100_000L, 10_000L, 200_000L);
        ModelUsage usage = ModelUsage.providerReported(1_000, 250, 500, 1_500);

        var result = calculator.calculate(pricing, usage);

        assertThat(result.status()).isEqualTo("ESTIMATED");
        assertThat(result.estimatedCostMicros()).isEqualTo(178L);
    }

    @Test
    void neverTreatsMissingUsageOrPricingAsZeroCost() {
        Pricing priced = new Pricing(
                "provider-price/v1", "USD",
                100_000L, 10_000L, 200_000L);

        assertThat(calculator.calculate(priced, ModelUsage.notAvailable()).status())
                .isEqualTo("USAGE_UNAVAILABLE");
        assertThat(calculator.calculate(priced, ModelUsage.notAvailable())
                .estimatedCostMicros()).isNull();
        assertThat(calculator.calculate(Pricing.unpriced(),
                ModelUsage.providerReported(1, 0, 1, 2)).status())
                .isEqualTo("UNPRICED");
        assertThat(calculator.calculate(Pricing.unpriced(),
                ModelUsage.providerReported(1, 0, 1, 2)).estimatedCostMicros())
                .isNull();
    }

    @Test
    void rejectsInvalidUsageAndPriceValues() {
        assertThatThrownBy(() -> new ModelUsage(
                "PROVIDER_REPORTED", 10L, 11L, 2L, 12L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pricing(
                "provider-price/v1", "USD", -1L, 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
