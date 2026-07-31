package com.jarylee.medicalagent.agent.multi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.deepseek.DeepSeekResearchModel;
import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelInvocation;
import com.jarylee.medicalagent.agent.model.ModelRoute;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.agent.model.ResearchModel;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ProtocolSectionModel;
import com.jarylee.medicalagent.agent.model.ObservationalDesignModel;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;
import com.jarylee.medicalagent.safety.ExternalModelInputGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "medical.model", name = "mode", havingValue = "multi")
public class MultiModelRouter implements ModelRouter {
    private static final Map<LogicalModelType, String> ROUTE_KEYS = Map.of(
            LogicalModelType.RESEARCH_FAST, "fast",
            LogicalModelType.RESEARCH_STANDARD, "standard",
            LogicalModelType.RESEARCH_REASONING, "reasoning",
            LogicalModelType.RESEARCH_REVIEW, "review");

    private final Map<LogicalModelType, ModelRoute> routes;

    public MultiModelRouter(
            ObjectMapper json,
            RestClient.Builder restClientBuilder,
            ExternalModelInputGuard inputGuard,
            Environment environment,
            @Value("${medical.model.external-enabled:false}") boolean externalEnabled,
            @Value("${medical.model.route-policy-version:multi-route-policy/v1}")
            String policyVersion,
            @Value("${medical.model.allow-insecure-http:false}") boolean allowInsecureHttp) {
        if (!externalEnabled) {
            throw new IllegalStateException(
                    "多模型模式需要显式设置 MEDICAL_MODEL_EXTERNAL_ENABLED=true");
        }
        Binder binder = Binder.get(environment);
        EnumMap<LogicalModelType, ModelRoute> configured =
                new EnumMap<>(LogicalModelType.class);
        for (LogicalModelType type : LogicalModelType.values()) {
            String prefix = "medical.model.routes." + ROUTE_KEYS.get(type);
            String provider = required(environment, prefix + ".provider");
            String modelName = required(environment, prefix + ".model");
            String baseUrl = required(environment, prefix + ".base-url");
            validateBaseUrl(baseUrl, allowInsecureHttp);
            String apiKey = environment.getProperty(prefix + ".api-key", "");
            String apiKeyFile = environment.getProperty(prefix + ".api-key-file", "");
            Duration connectTimeout = binder.bind(
                            prefix + ".connect-timeout", Duration.class)
                    .orElse(Duration.ofSeconds(5));
            Duration readTimeout = binder.bind(
                            prefix + ".read-timeout", Duration.class)
                    .orElse(Duration.ofSeconds(60));
            var delegate = new DeepSeekResearchModel(
                    json,
                    restClientBuilder.clone(),
                    true,
                    baseUrl,
                    modelName,
                    apiKey,
                    apiKeyFile,
                    connectTimeout,
                    readTimeout,
                    inputGuard);
            ResearchModel model = new ProviderNamedModel(provider, modelName, delegate);
            ModelRoute.Pricing routePricing = pricing(environment, prefix);
            if (!routePricing.priced()) {
                throw new IllegalStateException(
                        "真实多模型路由必须配置版本化价格: " + prefix + ".pricing");
            }
            configured.put(type, new ModelRoute(
                    type,
                    model,
                    policyVersion,
                    "CONFIGURED_" + ROUTE_KEYS.get(type).toUpperCase(),
                    routePricing));
        }
        this.routes = Map.copyOf(configured);
        requireDistinct(
                configured.get(LogicalModelType.RESEARCH_STANDARD),
                configured.get(LogicalModelType.RESEARCH_REVIEW));
        requireDistinct(
                configured.get(LogicalModelType.RESEARCH_REASONING),
                configured.get(LogicalModelType.RESEARCH_REVIEW));
    }

    @Override
    public ResearchModel route(LogicalModelType logicalModelType) {
        return resolve(logicalModelType).model();
    }

    @Override
    public ModelRoute resolve(LogicalModelType logicalModelType) {
        ModelRoute route = routes.get(logicalModelType);
        if (route == null) throw new IllegalArgumentException("未知逻辑模型类型");
        return route;
    }

    private static ModelRoute.Pricing pricing(Environment environment, String prefix) {
        String version = environment.getProperty(prefix + ".pricing.version");
        if (version == null || version.isBlank()) return ModelRoute.Pricing.unpriced();
        String currency = required(environment, prefix + ".pricing.currency");
        long input = requiredLong(environment, prefix + ".pricing.input-micros-per-million");
        long cached = environment.getProperty(
                prefix + ".pricing.cached-input-micros-per-million",
                Long.class, input);
        long output = requiredLong(environment, prefix + ".pricing.output-micros-per-million");
        return new ModelRoute.Pricing(version, currency, input, cached, output);
    }

    private static void requireDistinct(ModelRoute generation, ModelRoute review) {
        if (generation.identity().equals(review.identity())) {
            throw new IllegalStateException(
                    "复核模型必须与 STANDARD/REASONING 生成模型使用不同的 Provider 或模型");
        }
    }

    private static void validateBaseUrl(String value, boolean allowInsecureHttp) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception exception) {
            throw new IllegalStateException("多模型 Base URL 不合法");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException("多模型 Base URL 不合法");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !(allowInsecureHttp && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException("多模型 Base URL 必须使用 HTTPS");
        }
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少多模型配置: " + key);
        }
        return value.strip();
    }

    private static long requiredLong(Environment environment, String key) {
        Long value = environment.getProperty(key, Long.class);
        if (value == null) throw new IllegalStateException("缺少多模型配置: " + key);
        return value;
    }

    private record ProviderNamedModel(
            String configuredProvider,
            String configuredModelName,
            ResearchModel delegate
    ) implements ResearchModel {
        @Override
        public AnalysisResult analyzeIdea(String idea) {
            return delegate.analyzeIdea(idea);
        }

        @Override
        public AnalysisResult analyzeIdea(String idea, VersionedPrompt prompt) {
            return delegate.analyzeIdea(idea, prompt);
        }

        @Override
        public ModelInvocation<AnalysisResult> invokeAnalysis(
                String idea, VersionedPrompt prompt) {
            return delegate.invokeAnalysis(idea, prompt);
        }

        @Override
        public ModelInvocation<ProtocolSectionModel.GenerationCandidate>
        generateProtocolSection(
                ProtocolSectionModel.GenerationRequest request,
                VersionedPrompt prompt) {
            return delegate.generateProtocolSection(request, prompt);
        }

        @Override
        public ModelInvocation<ProtocolSectionModel.ReviewAdvisory>
        reviewProtocolSection(
                ProtocolSectionModel.ReviewRequest request,
                VersionedPrompt prompt) {
            return delegate.reviewProtocolSection(request, prompt);
        }

        @Override
        public ModelInvocation<ObservationalDesignModel.Advice>
        adviseObservationalDesign(
                ObservationalDesignModel.AdviceRequest request,
                VersionedPrompt prompt) {
            return delegate.adviseObservationalDesign(request, prompt);
        }

        @Override
        public String provider() {
            return configuredProvider;
        }

        @Override
        public String modelName() {
            return configuredModelName;
        }
    }
}
