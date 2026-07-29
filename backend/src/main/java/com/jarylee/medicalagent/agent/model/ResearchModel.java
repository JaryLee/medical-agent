package com.jarylee.medicalagent.agent.model;

import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;

public interface ResearchModel {
    AnalysisResult analyzeIdea(String idea);
    default AnalysisResult analyzeIdea(String idea, VersionedPrompt prompt) {
        return analyzeIdea(idea);
    }
    String provider();
    String modelName();
}
