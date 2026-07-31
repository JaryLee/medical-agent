package com.jarylee.medicalagent.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.mock.MockModelRouter;
import com.jarylee.medicalagent.agent.mock.MockResearchModel;
import com.jarylee.medicalagent.agent.multi.MultiModelRouter;
import com.jarylee.medicalagent.safety.ExternalModelInputGuard;
import com.jarylee.medicalagent.safety.PromptInjectionPolicy;
import com.jarylee.medicalagent.safety.SensitiveContentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRoutingTest {
    @Test
    void mockRoutesExposeDifferentStableModelIdentities() {
        ModelRouter router = new MockModelRouter(new MockResearchModel());

        assertThat(router.resolve(LogicalModelType.RESEARCH_FAST).identity())
                .isNotEqualTo(router.resolve(LogicalModelType.RESEARCH_STANDARD).identity());
        assertThat(router.resolve(LogicalModelType.RESEARCH_REVIEW).identity())
                .isNotEqualTo(router.resolve(LogicalModelType.RESEARCH_STANDARD).identity());
        router.requireIndependentReview(LogicalModelType.RESEARCH_STANDARD);
    }

    @Test
    void multiRoutesRequireExternalAuthorizationPricingAndIndependentReview() {
        MockEnvironment valid = configuredEnvironment();
        MultiModelRouter router = create(valid, true);

        assertThat(router.resolve(LogicalModelType.RESEARCH_FAST).model().modelName())
                .isEqualTo("fast-model");
        assertThat(router.resolve(LogicalModelType.RESEARCH_REVIEW).model().provider())
                .isEqualTo("provider-b");
        assertThat(router.resolve(LogicalModelType.RESEARCH_STANDARD).pricing().currency())
                .isEqualTo("USD");
        router.requireIndependentReview(LogicalModelType.RESEARCH_STANDARD);

        assertThatThrownBy(() -> create(configuredEnvironment(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MEDICAL_MODEL_EXTERNAL_ENABLED");

        MockEnvironment sameReview = configuredEnvironment()
                .withProperty("medical.model.routes.review.provider", "provider-a")
                .withProperty("medical.model.routes.review.model", "standard-model");
        assertThatThrownBy(() -> create(sameReview, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("复核模型");

        MockEnvironment unpriced = configuredEnvironment()
                .withProperty("medical.model.routes.fast.pricing.version", "");
        assertThatThrownBy(() -> create(unpriced, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("版本化价格");
    }

    private MultiModelRouter create(MockEnvironment environment, boolean enabled) {
        return new MultiModelRouter(
                new ObjectMapper(),
                RestClient.builder(),
                new ExternalModelInputGuard(
                        new SensitiveContentPolicy(), new PromptInjectionPolicy()),
                environment,
                enabled,
                "multi-route-policy/v1",
                false);
    }

    private MockEnvironment configuredEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        route(environment, "fast", "provider-a", "fast-model");
        route(environment, "standard", "provider-a", "standard-model");
        route(environment, "reasoning", "provider-a", "reasoning-model");
        route(environment, "review", "provider-b", "review-model");
        return environment;
    }

    private void route(
            MockEnvironment environment,
            String key,
            String provider,
            String model) {
        String prefix = "medical.model.routes." + key;
        environment
                .withProperty(prefix + ".provider", provider)
                .withProperty(prefix + ".model", model)
                .withProperty(prefix + ".base-url", "https://" + key + ".example.test")
                .withProperty(prefix + ".api-key", "test-secret")
                .withProperty(prefix + ".pricing.version", "test-pricing/v1")
                .withProperty(prefix + ".pricing.currency", "USD")
                .withProperty(prefix + ".pricing.input-micros-per-million", "100000")
                .withProperty(prefix + ".pricing.cached-input-micros-per-million", "10000")
                .withProperty(prefix + ".pricing.output-micros-per-million", "200000");
    }
}
