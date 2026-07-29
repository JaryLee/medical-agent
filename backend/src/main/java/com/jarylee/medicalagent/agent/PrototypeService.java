package com.jarylee.medicalagent.agent;

import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.agent.model.ResearchModels.*;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import com.jarylee.medicalagent.document.ControlledDocxService;
import com.jarylee.medicalagent.literature.LiteratureGateway;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class PrototypeService {
    private final ModelRouter modelRouter;
    private final LiteratureGateway literatureGateway;
    private final ControlledDocxService docxService;
    private final ResearchOutputValidator outputValidator;
    private final PromptTemplateRegistry prompts;

    public PrototypeService(ModelRouter modelRouter, LiteratureGateway literatureGateway,
                            ControlledDocxService docxService, ResearchOutputValidator outputValidator,
                            PromptTemplateRegistry prompts) {
        this.modelRouter = modelRouter;
        this.literatureGateway = literatureGateway;
        this.docxService = docxService;
        this.outputValidator = outputValidator;
        this.prompts = prompts;
    }

    public AnalysisResult analyze(String idea) {
        return outputValidator.validate(
                modelRouter.route(LogicalModelType.RESEARCH_FAST).analyzeIdea(
                        idea, prompts.require("STEP_01_PARSE_IDEA")));
    }

    public ResearchQuestionResult buildResearchQuestion(String idea, String directionId) {
        AnalysisResult analysis = analyze(idea);
        ResearchDirection selected = analysis.directions().stream()
                .filter(direction -> direction.id().equals(directionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知研究方向: " + directionId));
        PecoDefinition peco = new PecoDefinition("peco/v1", selected.population(), selected.exposure(),
                "未暴露或替代暴露组（需医生确认）", selected.outcome(),
                "在" + selected.population() + "中，" + selected.exposure() + "与" + selected.outcome() + "是否相关？",
                selected.recommendedStudyType(), List.of("主要终点定义", "观察时间", "混杂因素清单"));
        return new ResearchQuestionResult(analysis, selected, peco);
    }

    public PrototypeResult run(String idea, String directionId) {
        ResearchQuestionResult question = buildResearchQuestion(idea, directionId);
        String query = "(diabetes mellitus[MeSH Terms]) AND (SGLT2 inhibitor) AND (kidney function)";
        List<LiteratureRecord> literature = literatureGateway.search(query);
        String background = "慢性肾脏病是糖尿病人群的重要并发症。公开文献提示，SGLT2抑制剂与慢性肾脏病人群的肾脏结局改善相关"
                + " [CIT-001, CIT-002]。现有证据不能替代目标医院人群中的观察性验证，且本原型仅使用摘要级Mock快照，"
                + "具体因果解释、适用人群和终点定义均需医学与统计学专家确认。";
        return new PrototypeResult(
                question.analysis(), question.selectedDirection(), question.peco(),
                query, literature, background,
                "基于当前检索数据库、检索式和检索日期，暂未发现高度相似研究；该结论不代表完成了全部数据库和灰色文献检索。");
    }

    public byte[] export(String idea, String directionId) throws IOException {
        return docxService.render(run(idea, directionId));
    }
}
