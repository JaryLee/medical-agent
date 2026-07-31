package com.jarylee.medicalagent.agent.mock;

import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelInvocation;
import com.jarylee.medicalagent.agent.model.ModelRoute;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.agent.model.ResearchModel;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ProtocolSectionModel;
import com.jarylee.medicalagent.agent.model.ObservationalDesignModel;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "medical.model", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockModelRouter implements ModelRouter {
    private final Map<LogicalModelType, ResearchModel> models;

    public MockModelRouter(MockResearchModel model) {
        EnumMap<LogicalModelType, ResearchModel> configured =
                new EnumMap<>(LogicalModelType.class);
        for (LogicalModelType type : LogicalModelType.values()) {
            configured.put(type, new NamedMockModel(model, type));
        }
        this.models = Map.copyOf(configured);
    }

    @Override
    public ResearchModel route(LogicalModelType logicalModelType) {
        ResearchModel model = models.get(logicalModelType);
        if (model == null) throw new IllegalArgumentException("未知逻辑模型类型");
        return model;
    }

    @Override
    public ModelRoute resolve(LogicalModelType logicalModelType) {
        return new ModelRoute(
                logicalModelType,
                route(logicalModelType),
                "mock-routing/v2",
                "DETERMINISTIC_TEST_DEFAULT",
                ModelRoute.Pricing.unpriced());
    }

    private record NamedMockModel(
            MockResearchModel delegate,
            LogicalModelType logicalModelType
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
            return new ModelInvocation<>(
                    delegate.analyzeIdea(idea, prompt),
                    null,
                    "SYNTHETIC_TEST",
                    new ModelInvocation.ModelUsage(
                            "SYNTHETIC_TEST", null, null, null, null));
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
            return delegate.provider();
        }

        @Override
        public String modelName() {
            return delegate.modelName() + "-" + logicalModelType.name().toLowerCase();
        }
    }
}
