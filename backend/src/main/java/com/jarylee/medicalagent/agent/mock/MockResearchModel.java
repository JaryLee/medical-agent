package com.jarylee.medicalagent.agent.mock;

import com.jarylee.medicalagent.agent.model.ResearchModel;
import com.jarylee.medicalagent.agent.model.ResearchModels.*;
import com.jarylee.medicalagent.agent.model.ModelInvocation;
import com.jarylee.medicalagent.agent.model.ProtocolSectionModel;
import com.jarylee.medicalagent.agent.model.ObservationalDesignModel;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class MockResearchModel implements ResearchModel {
    private static final String DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批。"
                    + "研究设计、统计方案与医学判断须由相应专家确认。";

    @Override
    public AnalysisResult analyzeIdea(String idea) {
        if (idea == null || idea.isBlank()) {
            throw new IllegalArgumentException("研究想法不能为空");
        }
        String normalized = idea.toLowerCase(Locale.ROOT);
        String population = containsAny(normalized, "糖尿病", "2型糖尿病") ? "2型糖尿病成年患者" : "目标疾病成年患者";
        String exposure = containsAny(normalized, "sglt2", "降糖药", "药物") ? "目标降糖药物暴露" : "待确认暴露因素";
        String outcome = containsAny(normalized, "肾", "egfr", "肌酐") ? "肾功能变化（eGFR/肌酐）" : "待确认主要结局";
        List<String> missing = List.of("研究场景和数据来源", "观察时间范围", "对照定义", "主要结局的操作性定义");
        ResearchIdeaProfile profile = new ResearchIdeaProfile(
                "research-idea-profile/v1", "内分泌与肾脏相关", idea, population, exposure,
                "未暴露或替代暴露组（待确认）", outcome, "待确认", "待确认",
                "探索暴露与结局的关联", missing);
        List<String> questions = List.of(
                "研究对象来自门诊、住院还是体检数据库？",
                "暴露和对照如何定义，是否能获得起始用药日期？",
                "主要结局采用eGFR绝对变化、相对变化还是急性肾损伤发生率？",
                "计划观察多长时间，可获得哪些潜在混杂因素？");
        List<ResearchDirection> directions = List.of(
                direction("DIR-01", "目标人群肾功能现状及相关因素的横断面研究", StudyType.CROSS_SECTIONAL,
                        population, exposure, outcome, "描述现状并探索同期相关因素"),
                direction("DIR-02", "目标药物暴露与肾功能变化的回顾性队列研究", StudyType.COHORT,
                        population, exposure, outcome, "比较暴露后结局变化并控制混杂"),
                direction("DIR-03", "肾功能显著恶化患者的药物暴露病例对照研究", StudyType.CASE_CONTROL,
                        population, exposure, outcome, "从结局出发探索既往暴露差异"));
        return new AnalysisResult("research-analysis/v1", profile, questions, directions, DISCLAIMER);
    }

    @Override
    public AnalysisResult analyzeIdea(String idea, VersionedPrompt prompt) {
        if (prompt == null || prompt.version().isBlank() || !prompt.template().contains("${input}")) {
            throw new IllegalArgumentException("缺少版本化 Prompt");
        }
        return analyzeIdea(idea);
    }

    @Override
    public ModelInvocation<ProtocolSectionModel.GenerationCandidate>
    generateProtocolSection(
            ProtocolSectionModel.GenerationRequest request,
            VersionedPrompt prompt) {
        requirePrompt(prompt);
        if (request == null || request.sectionCode() == null
                || request.currentContent() == null) {
            throw new IllegalArgumentException("章节生成输入不完整");
        }
        List<String> evidence = request.allowedEvidenceIdentifiers() == null
                ? List.of()
                : request.allowedEvidenceIdentifiers().stream().limit(2).toList();
        String evidenceText = evidence.isEmpty()
                ? ""
                : "\n\n依据标识：" + String.join("、", evidence) + "。";
        String content = request.currentContent().strip()
                .replaceAll("(?i)STEP[_-]?\\d{1,3}", "后续流程")
                + "\n\n模型辅助候选补充：本节依据已确认事实整理，"
                + "所有方法、变量和结论仍需医学与统计专家确认。"
                + evidenceText;
        var candidate = new ProtocolSectionModel.GenerationCandidate(
                ProtocolSectionModel.GENERATION_OUTPUT_SCHEMA,
                request.sectionCode(),
                content,
                evidence,
                List.of("由医生确认本次模型辅助补充是否准确"),
                List.of("本候选仅用于科研设计讨论，不替代人工审核"));
        return new ModelInvocation<>(
                candidate,
                null,
                "SYNTHETIC_TEST",
                new ModelInvocation.ModelUsage(
                        "SYNTHETIC_TEST", null, null, null, null));
    }

    @Override
    public ModelInvocation<ProtocolSectionModel.ReviewAdvisory>
    reviewProtocolSection(
            ProtocolSectionModel.ReviewRequest request,
            VersionedPrompt prompt) {
        requirePrompt(prompt);
        var advisory = new ProtocolSectionModel.ReviewAdvisory(
                ProtocolSectionModel.REVIEW_OUTPUT_SCHEMA,
                "LOW",
                List.of(new ProtocolSectionModel.ReviewIssue(
                        "HUMAN_CONFIRMATION",
                        "LOW",
                        request.sectionCode(),
                        "模型候选仍需医学和统计专家人工确认。",
                        "保留待确认项并进入既有三方内部审核流程。")),
                "未发现结构化硬阻断；该结果仅为模型辅助复核建议。",
                true);
        return new ModelInvocation<>(
                advisory,
                null,
                "SYNTHETIC_TEST",
                new ModelInvocation.ModelUsage(
                        "SYNTHETIC_TEST", null, null, null, null));
    }

    @Override
    public ModelInvocation<ObservationalDesignModel.Advice>
    adviseObservationalDesign(
            ObservationalDesignModel.AdviceRequest request,
            VersionedPrompt prompt) {
        requirePrompt(prompt);
        if (request == null || request.recommendedStudyType() == null) {
            throw new IllegalArgumentException("观察性研究设计建议输入不完整");
        }
        var selected = request.alternatives().stream()
                .filter(value -> request.recommendedStudyType()
                        .equals(value.studyType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "规则推荐未出现在候选设计中"));
        var advice = new ObservationalDesignModel.Advice(
                ObservationalDesignModel.OUTPUT_SCHEMA,
                request.recommendedStudyType(),
                "ALIGNED",
                "模型建议与版本化规则推荐一致，仅补充偏倚和人工确认视角。",
                selected.biasRisks(),
                request.unresolvedItems(),
                request.requiredConfirmations(),
                List.of(
                        "仅供科研设计讨论，未经伦理和科研管理审批",
                        "模型建议不能覆盖规则结果，也不能替代研究者确认"),
                true);
        return new ModelInvocation<>(
                advice,
                null,
                "SYNTHETIC_TEST",
                new ModelInvocation.ModelUsage(
                        "SYNTHETIC_TEST", null, null, null, null));
    }

    private void requirePrompt(VersionedPrompt prompt) {
        if (prompt == null || prompt.version() == null || prompt.version().isBlank()
                || prompt.template() == null
                || !prompt.template().contains("${input}")) {
            throw new IllegalArgumentException("缺少版本化 Prompt");
        }
    }

    private ResearchDirection direction(String id, String title, StudyType type,
                                        String population, String exposure, String outcome, String purpose) {
        return new ResearchDirection(id, title, type, purpose, population, exposure, outcome,
                List.of("人口学信息", "用药记录", "基线与随访肾功能", "合并症与合并用药"),
                List.of("单中心选择偏倚", "残余混杂", "结局测量时间不一致"));
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    @Override public String provider() { return "mock"; }
    @Override public String modelName() { return "deterministic-stage0-v1"; }
}
