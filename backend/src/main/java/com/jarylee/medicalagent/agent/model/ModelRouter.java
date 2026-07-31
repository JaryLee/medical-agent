package com.jarylee.medicalagent.agent.model;

public interface ModelRouter {
    ResearchModel route(LogicalModelType logicalModelType);

    default ModelRoute resolve(LogicalModelType logicalModelType) {
        return new ModelRoute(
                logicalModelType,
                route(logicalModelType),
                "single-route/v1",
                "LEGACY_SINGLE_MODEL",
                ModelRoute.Pricing.unpriced());
    }

    default void requireIndependentReview(LogicalModelType generationType) {
        ModelRoute generation = resolve(generationType);
        ModelRoute review = resolve(LogicalModelType.RESEARCH_REVIEW);
        if (generation.identity().equals(review.identity())) {
            throw new IllegalStateException("复核模型必须与生成模型使用不同的 Provider 或模型");
        }
    }
}
