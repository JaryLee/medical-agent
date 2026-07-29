package com.jarylee.medicalagent.workflow;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StrobeChecklistRegistry {
    private final List<ItemDefinition> items = List.of(
            item(1, "标题与摘要", "在标题或摘要标明研究设计，并提供平衡的结构化摘要",
                    List.of("TITLE", "ABSTRACT"), "TITLE_ABSTRACT"),
            item(2, "引言", "说明研究背景与开展本研究的理由",
                    List.of("BACKGROUND"), "SECTION_REVIEW"),
            item(3, "引言", "陈述具体目标及预先设定的研究假设",
                    List.of("OBJECTIVES", "HYPOTHESIS"), "OBJECTIVES"),
            item(4, "方法", "在方法部分尽早说明研究设计的关键要素",
                    List.of("STUDY_DESIGN"), "STUDY_DESIGN"),
            item(5, "方法", "说明研究场景、地点和招募、暴露、随访及数据收集时间",
                    List.of("ABSTRACT", "PARTICIPANTS", "DATA_COLLECTION"), "PLACEHOLDER_REVIEW"),
            item(6, "方法", "按研究类型说明参与者来源、选择、随访或病例与对照确定方法",
                    List.of("PARTICIPANTS", "ELIGIBILITY", "STUDY_DESIGN"), "PLACEHOLDER_REVIEW"),
            item(7, "方法", "明确定义结局、暴露、预测因素、混杂因素和效应修饰因素",
                    List.of("OUTCOMES_VARIABLES"), "PLACEHOLDER_REVIEW"),
            item(8, "方法", "说明各变量的数据来源、测量方法和组间可比性",
                    List.of("DATA_COLLECTION", "OUTCOMES_VARIABLES"), "PLACEHOLDER_REVIEW"),
            item(9, "方法", "说明针对潜在偏倚来源采取的措施",
                    List.of("BIAS_CONTROL"), "PLACEHOLDER_REVIEW"),
            item(10, "方法", "说明研究样本量的形成依据",
                    List.of("STATISTICAL_ANALYSIS"), "SAMPLE_SIZE"),
            item(11, "方法", "说明定量变量在分析中的处理和分组依据",
                    List.of("STATISTICAL_ANALYSIS"), "STATISTICAL_REVIEW"),
            item(12, "方法", "说明统计方法、混杂控制、亚组、缺失数据及敏感性分析",
                    List.of("STATISTICAL_ANALYSIS"), "STATISTICAL_REVIEW"),
            item(13, "结果", "报告研究各阶段参与者数量、不参与原因并考虑流程图",
                    List.of(), "POST_STUDY_REVIEW"),
            item(14, "结果", "报告参与者特征、暴露、混杂因素、缺失数据及适用的随访时间",
                    List.of(), "POST_STUDY_REVIEW"),
            item(15, "结果", "按研究类型报告结局事件或暴露汇总数据",
                    List.of(), "POST_STUDY_REVIEW"),
            item(16, "结果", "报告未调整与调整后效应估计、精度及调整因素依据",
                    List.of(), "POST_STUDY_REVIEW"),
            item(17, "结果", "报告亚组、交互作用和敏感性等其他分析",
                    List.of(), "POST_STUDY_REVIEW"),
            item(18, "讨论", "结合研究目标概括主要结果",
                    List.of(), "POST_STUDY_REVIEW"),
            item(19, "讨论", "讨论偏倚或不精确性等局限及其可能方向和程度",
                    List.of("BIAS_CONTROL"), "POST_STUDY_REVIEW"),
            item(20, "讨论", "结合目标、局限、多重分析和其他证据谨慎解释结果",
                    List.of("RESEARCH_STATUS"), "POST_STUDY_REVIEW"),
            item(21, "讨论", "讨论研究结果的可推广性",
                    List.of(), "POST_STUDY_REVIEW"),
            item(22, "其他信息", "说明经费来源及资助方在研究中的角色",
                    List.of(), "FUNDING")
    );

    public List<ItemDefinition> items() {
        return items;
    }

    private static ItemDefinition item(
            int number,
            String group,
            String summary,
            List<String> sections,
            String rule) {
        return new ItemDefinition(
                "STROBE-" + String.format("%02d", number),
                group,
                summary,
                sections,
                rule);
    }

    public record ItemDefinition(
            String itemCode,
            String sectionGroup,
            String requirementSummary,
            List<String> mappedSectionCodes,
            String evaluationRule
    ) {}
}
