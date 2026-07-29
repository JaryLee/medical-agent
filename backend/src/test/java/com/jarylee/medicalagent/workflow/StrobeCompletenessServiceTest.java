package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StrobeCompletenessServiceTest {
    private static final List<String> SECTION_CODES = List.of(
            "TITLE", "ABSTRACT", "BACKGROUND", "RESEARCH_STATUS",
            "RESEARCH_GAP", "OBJECTIVES", "HYPOTHESIS", "STUDY_DESIGN",
            "PARTICIPANTS", "ELIGIBILITY", "OUTCOMES_VARIABLES",
            "DATA_COLLECTION", "STATISTICAL_ANALYSIS", "BIAS_CONTROL",
            "ETHICS_DATA_SECURITY", "SCHEDULE", "EXPECTED_RESULTS", "REFERENCES");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void checksTwentyTwoItemsWithoutCreatingAQualityScore() {
        var repository = new MemoryStrobeCompletenessRepository();
        var service = new StrobeCompletenessService(
                repository, new StrobeChecklistRegistry(), json, clock);
        UUID hospitalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var protocol = protocol();
        var statistics = statistics(protocol);
        var claims = claims(protocol);

        var result = service.execute(
                hospitalId, projectId, taskId, protocol, statistics, claims);
        var replayed = service.execute(
                hospitalId, projectId, taskId, protocol, statistics, claims);

        assertThat(result.schemaVersion())
                .isEqualTo("strobe-completeness-check-result/v1");
        assertThat(result.totalItemCount()).isEqualTo(22);
        assertThat(result.items())
                .extracting(StrobeCompletenessModels.CheckItem::itemCode)
                .containsExactly(
                        "STROBE-01", "STROBE-02", "STROBE-03", "STROBE-04",
                        "STROBE-05", "STROBE-06", "STROBE-07", "STROBE-08",
                        "STROBE-09", "STROBE-10", "STROBE-11", "STROBE-12",
                        "STROBE-13", "STROBE-14", "STROBE-15", "STROBE-16",
                        "STROBE-17", "STROBE-18", "STROBE-19", "STROBE-20",
                        "STROBE-21", "STROBE-22");
        assertThat(result.coveredCount() + result.partiallyCoveredCount()
                + result.missingCount() + result.notApplicableCount()
                + result.needsExpertReviewCount()).isEqualTo(22);
        assertThat(item(result, "STROBE-03").status()).isEqualTo("COVERED");
        assertThat(item(result, "STROBE-10").status()).isEqualTo("MISSING");
        assertThat(item(result, "STROBE-13").status())
                .isEqualTo("NEEDS_EXPERT_REVIEW");
        assertThat(item(result, "STROBE-22").status()).isEqualTo("MISSING");
        assertThat(result.automaticPrecheckDisclaimer())
                .contains("自动预检查", "不是研究质量评分工具");
        assertThat(json.valueToTree(result).toString())
                .doesNotContain("\"score\"", "\"percentage\"", "\"grade\"");
        assertThat(replayed.checkTaskId()).isEqualTo(result.checkTaskId());
        assertThat(repository.all()).hasSize(1);
        assertThat(repository.items(result.checkTaskId())).hasSize(22);
    }

    private StrobeCompletenessModels.CheckItem item(
            StrobeCompletenessModels.CheckResult result, String code) {
        return result.items().stream()
                .filter(value -> code.equals(value.itemCode()))
                .findFirst().orElseThrow();
    }

    private ResearchProtocolModels.ProtocolDraft protocol() {
        UUID protocolId = UUID.randomUUID();
        List<ResearchProtocolModels.ProtocolSection> sections =
                IntStream.range(0, SECTION_CODES.size())
                        .mapToObj(index -> {
                            String code = SECTION_CODES.get(index);
                            String content = switch (code) {
                                case "TITLE" -> "糖尿病患者肾功能结局的队列研究";
                                case "ABSTRACT" -> "本研究拟开展队列研究，主要结果尚待研究完成后报告。";
                                case "BACKGROUND" -> "说明当前临床问题和研究理由。";
                                case "OBJECTIVES" -> "主要目标：评估暴露与主要终点的统计学关联。";
                                case "HYPOTHESIS" -> "预设假设：暴露与主要终点存在可检验关联。";
                                case "STUDY_DESIGN" -> "采用队列研究设计并设置共同时间零点。";
                                case "PARTICIPANTS" -> "研究对象和研究场景待确认。";
                                case "ELIGIBILITY" -> "纳入与排除标准待定义。";
                                case "OUTCOMES_VARIABLES" ->
                                        "主要终点已确认，混杂因素和效应修饰因素待定义。";
                                case "DATA_COLLECTION" -> "数据来源、测量方法和时间窗待确认。";
                                case "STATISTICAL_ANALYSIS" ->
                                        "描述性统计、混杂控制和敏感性分析；MISSING_NEEDS_INPUT。";
                                case "BIAS_CONTROL" -> "偏倚来源及控制方法待专家确认。";
                                default -> code + " 方案草案。";
                            };
                            return new ResearchProtocolModels.ProtocolSection(
                                    UUID.randomUUID(), code, code, index + 1,
                                    "STATISTICAL_ANALYSIS".equals(code) ? 2 : 1,
                                    content, "MARKDOWN", "AGENT_DETERMINISTIC",
                                    "NEEDS_EXPERT_REVIEW", List.of(),
                                    List.of("待专家确认"));
                        })
                        .toList();
        return new ResearchProtocolModels.ProtocolDraft(
                ResearchProtocolGenerationService.RESULT_SCHEMA_VERSION,
                protocolId, clock.instant(), StudyType.COHORT,
                "队列研究方案", sections, List.of(), "0".repeat(64),
                ResearchProtocolGenerationService.GENERATOR_VERSION, List.of());
    }

    private StatisticalAnalysisModels.StatisticalDraft statistics(
            ResearchProtocolModels.ProtocolDraft protocol) {
        var statisticalSection = protocol.sections().stream()
                .filter(value -> "STATISTICAL_ANALYSIS".equals(value.sectionCode()))
                .findFirst().orElseThrow();
        List<StatisticalAnalysisModels.SampleSizeParameter> parameters =
                IntStream.range(0, 8)
                        .mapToObj(index ->
                                new StatisticalAnalysisModels.SampleSizeParameter(
                                        "PARAM_" + index, "样本量参数 " + index,
                                        true, "MISSING_NEEDS_INPUT", null, null,
                                        "由专家提供"))
                        .toList();
        return new StatisticalAnalysisModels.StatisticalDraft(
                StatisticalAnalysisDraftService.RESULT_SCHEMA_VERSION,
                UUID.randomUUID(), protocol.protocolId(), clock.instant(),
                StudyType.COHORT, "主要终点", "NEEDS_EXPERT_CONFIRMATION",
                List.of("描述性统计"), List.of("主要分析"), List.of("次要分析"),
                List.of("协变量"), List.of("混杂因素"), List.of("分层"),
                List.of("亚组"), List.of("敏感性"), List.of("缺失数据"),
                List.of("多重比较"), List.of("模型诊断"), List.of("风险比"),
                "置信区间待确认", parameters, List.of("R"),
                List.of("待专家确认"), statisticalSection, "1".repeat(64),
                StatisticalAnalysisDraftService.GENERATOR_VERSION, List.of());
    }

    private ClaimCitationValidationModels.ValidationResult claims(
            ResearchProtocolModels.ProtocolDraft protocol) {
        return new ClaimCitationValidationModels.ValidationResult(
                ClaimCitationValidationService.RESULT_SCHEMA_VERSION,
                UUID.randomUUID(), protocol.protocolId(), clock.instant(),
                0, 0, 0, 0,
                List.of(), "2".repeat(64),
                ClaimCitationValidationService.VALIDATOR_VERSION, List.of());
    }
}
