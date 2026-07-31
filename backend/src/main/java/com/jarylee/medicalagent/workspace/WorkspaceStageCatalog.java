package com.jarylee.medicalagent.workspace;

import java.util.List;
import java.util.Map;

final class WorkspaceStageCatalog {
    static final List<StageDefinition> STAGES = List.of(
            new StageDefinition("RESEARCH_IDEA", "研究构想", "idea"),
            new StageDefinition("RESEARCH_DIRECTION", "研究方向", "direction"),
            new StageDefinition("EVIDENCE", "证据检索与核验", "evidence"),
            new StageDefinition("STUDY_DESIGN", "研究设计", "design"),
            new StageDefinition("PROTOCOL", "研究方案", "protocol"),
            new StageDefinition("STATISTICS", "统计分析", "statistics"),
            new StageDefinition("QUALITY", "质量与报告规范", "quality"),
            new StageDefinition("INTERNAL_REVIEW", "内部审核", "review"),
            new StageDefinition("DRAFT_EXPORT", "科研草案导出", "export"));

    private static final Map<String, Integer> STEP_TO_STAGE = Map.ofEntries(
            Map.entry("STEP_01_PARSE_IDEA", 0),
            Map.entry("STEP_02_IDENTIFY_MISSING_INFORMATION", 0),
            Map.entry("STEP_03_ASK_CLARIFICATION", 0),
            Map.entry("STEP_04_GENERATE_RESEARCH_DIRECTIONS", 1),
            Map.entry("STEP_05_CONFIRM_DIRECTION", 1),
            Map.entry("STEP_06_BUILD_RESEARCH_QUESTION", 2),
            Map.entry("STEP_07_BUILD_SEARCH_STRATEGY", 2),
            Map.entry("STEP_08_SEARCH_PUBMED", 2),
            Map.entry("STEP_09_SEARCH_CLINICAL_TRIALS", 2),
            Map.entry("STEP_10_VALIDATE_LITERATURE", 2),
            Map.entry("STEP_11_ANALYZE_SIMILAR_RESEARCH", 2),
            Map.entry("STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN", 3),
            Map.entry("STEP_13_GENERATE_PROTOCOL_SECTIONS", 4),
            Map.entry("STEP_14_GENERATE_STATISTICAL_DRAFT", 5),
            Map.entry("STEP_15_VALIDATE_CLAIMS_AND_CITATIONS", 6),
            Map.entry("STEP_16_CHECK_STROBE_COMPLETENESS", 6),
            Map.entry("STEP_17_WAIT_EXPERT_REVIEW", 7),
            Map.entry("STEP_18_EXPORT_DOCUMENT", 8));

    private WorkspaceStageCatalog() {}

    static int indexForStep(String stepCode) {
        if (stepCode == null || stepCode.isBlank()) {
            throw new IllegalStateException(
                    "未注册的 Agent 步骤，不能生成业务读模型");
        }
        Integer index = STEP_TO_STAGE.get(stepCode);
        if (index == null) {
            throw new IllegalStateException("未注册的 Agent 步骤，不能生成业务读模型");
        }
        return index;
    }

    record StageDefinition(String code, String label, String routeSegment) {}
}
