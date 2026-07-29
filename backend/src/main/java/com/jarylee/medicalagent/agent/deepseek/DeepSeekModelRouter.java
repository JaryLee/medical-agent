package com.jarylee.medicalagent.agent.deepseek;

import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.agent.model.ResearchModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medical.model", name = "mode", havingValue = "deepseek")
public class DeepSeekModelRouter implements ModelRouter {
    private final DeepSeekResearchModel model;

    public DeepSeekModelRouter(DeepSeekResearchModel model) {
        this.model = model;
    }

    @Override
    public ResearchModel route(LogicalModelType logicalModelType) {
        return model;
    }
}
