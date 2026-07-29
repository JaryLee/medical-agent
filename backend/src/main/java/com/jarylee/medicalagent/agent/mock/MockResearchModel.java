package com.jarylee.medicalagent.agent.mock;

import com.jarylee.medicalagent.agent.model.ResearchModel;
import com.jarylee.medicalagent.agent.model.ResearchModels.*;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class MockResearchModel implements ResearchModel {
    private static final String DISCLAIMER =
            "系统仅提供科研建议，研究设计、统计方案与医学判断须由相应专家确认。";

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
