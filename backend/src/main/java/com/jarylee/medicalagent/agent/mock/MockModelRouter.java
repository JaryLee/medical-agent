package com.jarylee.medicalagent.agent.mock;

import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.agent.model.ResearchModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medical.model", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockModelRouter implements ModelRouter {
    private final MockResearchModel model;

    public MockModelRouter(MockResearchModel model) {
        this.model = model;
    }

    @Override
    public ResearchModel route(LogicalModelType logicalModelType) {
        return model;
    }
}
