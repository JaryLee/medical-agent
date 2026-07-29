package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StrobeCompletenessService {
    public static final String RESULT_SCHEMA_VERSION =
            "strobe-completeness-check-result/v1";
    public static final String CHECKER_VERSION =
            "deterministic-strobe-2007-precheck/v1";
    public static final String GUIDELINE_VERSION =
            "STROBE-2007-COMBINED/v1";
    public static final String SOURCE_REFERENCE =
            "https://www.strobe-statement.org/checklists/";
    public static final String DISCLAIMER =
            "自动预检查，不能替代医学、统计学或科研管理专家审核。"
                    + "STROBE 仅用于报告完整性检查，不是研究质量评分工具。";

    private final StrobeCompletenessRepository repository;
    private final StrobeChecklistRegistry registry;
    private final ObjectMapper json;
    private final Clock clock;

    public StrobeCompletenessService(
            StrobeCompletenessRepository repository,
            StrobeChecklistRegistry registry,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.json = json;
        this.clock = clock;
    }

    public StrobeCompletenessModels.CheckResult execute(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            ResearchProtocolModels.ProtocolDraft protocol,
            StatisticalAnalysisModels.StatisticalDraft statisticalDraft,
            ClaimCitationValidationModels.ValidationResult claimValidation) {
        requireInputs(
                hospitalId, projectId, agentTaskId,
                protocol, statisticalDraft, claimValidation);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("protocolDraft", protocol);
        snapshot.put("statisticalAnalysisDraft", statisticalDraft);
        snapshot.put("claimCitationValidation", claimValidation);
        String inputHash = sha256(writeBytes(snapshot));
        var existing = repository.findByAgentTask(hospitalId, agentTaskId);
        if (existing.isPresent()) {
            if (!inputHash.equals(existing.get().inputSha256())) {
                throw new IllegalStateException(
                        "同一 Agent 任务已存在输入不一致的 STROBE 预检查结果");
            }
            return read(existing.get().resultJson());
        }

        Map<String, ResearchProtocolModels.ProtocolSection> sections = new HashMap<>();
        protocol.sections().forEach(section -> sections.put(section.sectionCode(), section));
        List<StrobeCompletenessModels.CheckItem> items = registry.items().stream()
                .map(definition -> evaluate(
                        definition, protocol.studyType(), sections, statisticalDraft))
                .toList();
        Map<String, Integer> counts = new HashMap<>();
        items.forEach(item -> counts.merge(item.status(), 1, Integer::sum));
        UUID checkTaskId = UUID.randomUUID();
        Instant checkedAt = clock.instant();
        var result = new StrobeCompletenessModels.CheckResult(
                RESULT_SCHEMA_VERSION,
                checkTaskId,
                protocol.protocolId(),
                checkedAt,
                "STROBE",
                GUIDELINE_VERSION,
                protocol.studyType(),
                items.size(),
                count(counts, "COVERED"),
                count(counts, "PARTIALLY_COVERED"),
                count(counts, "MISSING"),
                count(counts, "NOT_APPLICABLE"),
                count(counts, "NEEDS_EXPERT_REVIEW"),
                items,
                inputHash,
                CHECKER_VERSION,
                SOURCE_REFERENCE,
                DISCLAIMER,
                List.of(
                        "当前输入是研究方案草案，不是已完成研究的论文报告；结果和讨论类条目只能标记为待专家复核。",
                        "本预检查只判断文本覆盖线索，不判断研究设计、统计方法、证据或结论的科学质量。",
                        "检查结果不包含总分、百分比、等级或排名，也不得据此宣称研究符合 STROBE。",
                        "采用 STROBE 22 个主条目的中文简要释义；正式投稿前应对照官方清单和解释文件逐项复核。"));
        repository.save(
                new StrobeCompletenessRepository.CheckTaskData(
                        checkTaskId,
                        hospitalId,
                        projectId,
                        agentTaskId,
                        protocol.protocolId(),
                        "COMPLETED",
                        protocol.studyType().name(),
                        result.totalItemCount(),
                        result.coveredCount(),
                        result.partiallyCoveredCount(),
                        result.missingCount(),
                        result.notApplicableCount(),
                        result.needsExpertReviewCount(),
                        inputHash,
                        CHECKER_VERSION,
                        write(result),
                        checkedAt),
                items);
        return result;
    }

    private StrobeCompletenessModels.CheckItem evaluate(
            StrobeChecklistRegistry.ItemDefinition definition,
            StudyType studyType,
            Map<String, ResearchProtocolModels.ProtocolSection> sections,
            StatisticalAnalysisModels.StatisticalDraft statisticalDraft) {
        List<ResearchProtocolModels.ProtocolSection> mapped =
                definition.mappedSectionCodes().stream()
                        .map(sections::get)
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparingInt(
                                ResearchProtocolModels.ProtocolSection::sortOrder))
                        .toList();
        Evaluation evaluation = switch (definition.evaluationRule()) {
            case "TITLE_ABSTRACT" -> titleAbstract(mapped, studyType);
            case "OBJECTIVES" -> sectionPresence(mapped, 2, false);
            case "STUDY_DESIGN" -> studyDesign(mapped, studyType);
            case "PLACEHOLDER_REVIEW" -> placeholderReview(mapped);
            case "SECTION_REVIEW" -> sectionReview(mapped);
            case "SAMPLE_SIZE" -> sampleSize(statisticalDraft);
            case "STATISTICAL_REVIEW" -> statisticalReview(mapped);
            case "POST_STUDY_REVIEW" -> postStudyReview();
            case "FUNDING" -> new Evaluation(
                    "MISSING",
                    "当前方案没有经费来源和资助方角色章节。",
                    "补充经费来源、项目编号及资助方在设计、实施、分析和发表中的角色。",
                    false);
            default -> throw new IllegalStateException(
                    "未知 STROBE 检查规则: " + definition.evaluationRule());
        };
        return new StrobeCompletenessModels.CheckItem(
                UUID.randomUUID(),
                definition.itemCode(),
                definition.sectionGroup(),
                definition.requirementSummary(),
                studyType,
                evaluation.status(),
                mapped.stream()
                        .map(ResearchProtocolModels.ProtocolSection::sectionCode)
                        .toList(),
                mapped.stream()
                        .map(section -> truncate(clean(section.content()), 260))
                        .toList(),
                evaluation.message(),
                evaluation.suggestion(),
                evaluation.requiresExpertReview());
    }

    private Evaluation titleAbstract(
            List<ResearchProtocolModels.ProtocolSection> mapped,
            StudyType studyType) {
        if (mapped.size() < 2) {
            return missing("标题或摘要章节缺失。", "补充研究设计名称和结构化摘要。");
        }
        String combined = combine(mapped);
        boolean hasDesign = combined.contains(studyTypeLabel(studyType));
        if (!hasDesign) {
            return missing(
                    "标题和摘要未明确使用常用研究设计名称。",
                    "在标题或摘要中明确标注" + studyTypeLabel(studyType) + "。");
        }
        return new Evaluation(
                "PARTIALLY_COVERED",
                "已标明研究设计并概述拟开展内容，但方案阶段尚无研究结果摘要。",
                "研究完成后补充方法、主要结果与局限的平衡摘要。",
                true);
    }

    private Evaluation sectionPresence(
            List<ResearchProtocolModels.ProtocolSection> mapped,
            int expected,
            boolean reviewPlaceholders) {
        if (mapped.size() < expected) {
            return missing("所需方案章节不完整。", "补充缺失章节后重新检查。");
        }
        if (reviewPlaceholders && hasPlaceholder(mapped)) return partial();
        return new Evaluation(
                "COVERED", "当前方案已提供对应报告要素。",
                "由专家复核表述的准确性和最终版本。", true);
    }

    private Evaluation studyDesign(
            List<ResearchProtocolModels.ProtocolSection> mapped,
            StudyType studyType) {
        if (mapped.isEmpty()
                || !combine(mapped).contains(studyTypeLabel(studyType))) {
            return missing(
                    "研究设计章节未明确设计类型。",
                    "在方法开头明确" + studyTypeLabel(studyType) + "及关键设计要素。");
        }
        return new Evaluation(
                "COVERED", "研究设计章节已明确设计类型及关键框架。",
                "由流行病学专家复核设计细节。", true);
    }

    private Evaluation placeholderReview(
            List<ResearchProtocolModels.ProtocolSection> mapped) {
        if (mapped.isEmpty()) {
            return missing("未找到对应方法章节。", "补充相应方法要素。");
        }
        return hasPlaceholder(mapped)
                ? partial()
                : new Evaluation(
                        "COVERED", "当前方案已提供对应方法要素。",
                        "由专家复核定义和执行可操作性。", true);
    }

    private Evaluation sectionReview(
            List<ResearchProtocolModels.ProtocolSection> mapped) {
        if (mapped.isEmpty()) {
            return missing("未找到对应章节。", "补充对应报告内容。");
        }
        return mapped.stream().anyMatch(section -> !section.issuesToConfirm().isEmpty())
                ? partial()
                : new Evaluation(
                        "COVERED", "当前方案已覆盖该报告要素。",
                        "由专家复核事实依据和最终措辞。", true);
    }

    private Evaluation sampleSize(
            StatisticalAnalysisModels.StatisticalDraft draft) {
        long confirmed = draft.sampleSizeParameters().stream()
                .filter(value -> "CONFIRMED".equals(value.valueStatus())
                        && value.value() != null && !value.value().isBlank())
                .count();
        if (confirmed == draft.sampleSizeParameters().size()) {
            return new Evaluation(
                    "COVERED", "样本量参数均已确认。",
                    "补充确定性计算公式、软件版本和计算记录。", true);
        }
        if (confirmed > 0) {
            return new Evaluation(
                    "PARTIALLY_COVERED", "仅部分样本量参数已确认。",
                    "补齐全部参数并记录计算方法、软件版本和调整依据。", true);
        }
        return missing(
                "所有样本量参数仍为待输入状态，尚无样本量形成依据。",
                "由临床和统计学专家提供并确认参数后执行可复现计算。");
    }

    private Evaluation statisticalReview(
            List<ResearchProtocolModels.ProtocolSection> mapped) {
        if (mapped.isEmpty()) {
            return missing("统计分析章节缺失。", "补充统计分析计划。");
        }
        return new Evaluation(
                "PARTIALLY_COVERED",
                "已提供条件化统计方法草案，但变量类型、参数和最终方法仍待确认。",
                "由统计学专家确认变量处理、混杂控制、亚组、缺失数据和敏感性分析。",
                true);
    }

    private Evaluation postStudyReview() {
        return new Evaluation(
                "NEEDS_EXPERT_REVIEW",
                "当前是研究方案阶段，尚无研究结果或正式讨论文本，不能自动判断该报告条目。",
                "研究完成并形成报告后，由专家结合真实分析结果逐项复核。",
                true);
    }

    private Evaluation partial() {
        return new Evaluation(
                "PARTIALLY_COVERED",
                "当前章节提供了部分覆盖线索，但仍含待确认、待定义或待补充内容。",
                "补齐可执行定义、时间、来源和专家确认信息后重新检查。",
                true);
    }

    private Evaluation missing(String message, String suggestion) {
        return new Evaluation("MISSING", message, suggestion, false);
    }

    private boolean hasPlaceholder(
            List<ResearchProtocolModels.ProtocolSection> sections) {
        String value = combine(sections);
        return List.of("待确认", "待定义", "待补充", "待排期", "MISSING_NEEDS_INPUT")
                .stream().anyMatch(value::contains);
    }

    private String combine(List<ResearchProtocolModels.ProtocolSection> sections) {
        return sections.stream()
                .map(ResearchProtocolModels.ProtocolSection::content)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private String studyTypeLabel(StudyType studyType) {
        return switch (studyType) {
            case COHORT -> "队列研究";
            case CROSS_SECTIONAL -> "横断面研究";
            case CASE_CONTROL -> "病例对照研究";
        };
    }

    private int count(Map<String, Integer> values, String status) {
        return values.getOrDefault(status, 0);
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private void requireInputs(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            ResearchProtocolModels.ProtocolDraft protocol,
            StatisticalAnalysisModels.StatisticalDraft statisticalDraft,
            ClaimCitationValidationModels.ValidationResult claimValidation) {
        if (hospitalId == null || projectId == null || agentTaskId == null
                || protocol == null || statisticalDraft == null
                || claimValidation == null) {
            throw new IllegalArgumentException("STROBE 预检查输入不完整");
        }
        if (!ResearchProtocolGenerationService.RESULT_SCHEMA_VERSION.equals(
                protocol.schemaVersion())
                || protocol.sections() == null || protocol.sections().size() != 18) {
            throw new IllegalStateException("研究方案章节未完整生成");
        }
        if (!StatisticalAnalysisDraftService.RESULT_SCHEMA_VERSION.equals(
                statisticalDraft.schemaVersion())
                || statisticalDraft.statisticalSectionVersion().versionNo() != 2) {
            throw new IllegalStateException("STEP14 统计分析草案未完成");
        }
        if (!ClaimCitationValidationService.RESULT_SCHEMA_VERSION.equals(
                claimValidation.schemaVersion())
                || !claimValidation.protocolId().equals(protocol.protocolId())) {
            throw new IllegalStateException("STEP15 主张与引用验证未完成");
        }
    }

    private byte[] writeBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException("STROBE 预检查输入序列化失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("STROBE 预检查结果序列化失败", exception);
        }
    }

    private StrobeCompletenessModels.CheckResult read(String value) {
        try {
            return json.readValue(value, StrobeCompletenessModels.CheckResult.class);
        } catch (Exception exception) {
            throw new IllegalStateException("已持久化 STROBE 预检查结果损坏", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("STROBE 预检查哈希失败", exception);
        }
    }

    private record Evaluation(
            String status,
            String message,
            String suggestion,
            boolean requiresExpertReview
    ) {}
}
