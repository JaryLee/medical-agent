package com.jarylee.medicalagent.agent.model;

import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ProtocolSectionModel.GenerationCandidate;
import com.jarylee.medicalagent.agent.model.ProtocolSectionModel.GenerationRequest;
import com.jarylee.medicalagent.agent.model.ProtocolSectionModel.ReviewAdvisory;
import com.jarylee.medicalagent.agent.model.ProtocolSectionModel.ReviewRequest;
import com.jarylee.medicalagent.agent.model.ObservationalDesignModel.Advice;
import com.jarylee.medicalagent.agent.model.ObservationalDesignModel.AdviceRequest;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;

public interface ResearchModel {
    AnalysisResult analyzeIdea(String idea);
    default AnalysisResult analyzeIdea(String idea, VersionedPrompt prompt) {
        return analyzeIdea(idea);
    }
    default ModelInvocation<AnalysisResult> invokeAnalysis(
            String idea, VersionedPrompt prompt) {
        return ModelInvocation.unmetered(analyzeIdea(idea, prompt));
    }
    default ModelInvocation<GenerationCandidate> generateProtocolSection(
            GenerationRequest request, VersionedPrompt prompt) {
        throw new UnsupportedOperationException("当前模型不支持章节级生成");
    }
    default ModelInvocation<ReviewAdvisory> reviewProtocolSection(
            ReviewRequest request, VersionedPrompt prompt) {
        throw new UnsupportedOperationException("当前模型不支持章节级复核");
    }
    default ModelInvocation<Advice> adviseObservationalDesign(
            AdviceRequest request, VersionedPrompt prompt) {
        throw new UnsupportedOperationException("当前模型不支持观察性研究设计建议");
    }
    String provider();
    String modelName();
}
