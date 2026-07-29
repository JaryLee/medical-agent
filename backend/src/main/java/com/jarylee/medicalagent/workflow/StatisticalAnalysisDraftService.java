package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StatisticalAnalysisDraftService {
    public static final String RESULT_SCHEMA_VERSION = "statistical-analysis-draft/v1";
    public static final String GENERATOR_VERSION =
            "deterministic-observational-statistics/v1";

    private final StatisticalAnalysisDraftRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public StatisticalAnalysisDraftService(
            StatisticalAnalysisDraftRepository repository,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    public StatisticalAnalysisModels.StatisticalDraft execute(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            ResearchProtocolModels.ProtocolDraft protocol,
            ObservationalDesignRecommendationModels.Recommendation design) {
        requireInputs(hospitalId, projectId, agentTaskId, protocol, design);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("protocolDraft", protocol);
        snapshot.put("confirmedObservationalDesign", design);
        String inputHash = sha256(writeBytes(snapshot));
        var existing = repository.findByAgentTask(hospitalId, agentTaskId);
        if (existing.isPresent()) {
            if (!inputHash.equals(existing.get().inputSha256())) {
                throw new IllegalStateException(
                        "同一 Agent 任务已存在输入不一致的统计分析草案");
            }
            return read(existing.get().resultJson());
        }

        UUID draftId = UUID.randomUUID();
        Instant generatedAt = clock.instant();
        StudyType studyType = design.confirmedStudyType();
        String primaryOutcome = design.confirmedPrimaryOutcome();
        List<StatisticalAnalysisModels.SampleSizeParameter> parameters =
                sampleSizeParameters(studyType);
        List<String> descriptive = descriptiveAnalysis(studyType);
        List<String> primary = primaryAnalysis(studyType);
        List<String> secondary = List.of(
                "次要终点尚未完成结构化确认；确认后按终点类型分别报告效应量与置信区间。",
                "探索性分析必须与主要分析明确区分，并在结果解释中标记探索性质。");
        List<String> covariates = List.of(
                "最终协变量清单待变量字典、临床意义、数据可用性和预先指定原则共同确认。",
                "不以单变量显著性作为唯一入模标准，不在看到主要结果后静默选择协变量。");
        List<String> confounders = List.of(
                "候选混杂因素：人口学特征、基线疾病严重程度、合并症、合并用药和医疗服务利用。",
                "每个候选因素必须记录定义、测量时间、数据来源、缺失情况及纳入依据。");
        List<String> stratified = List.of(
                "按关键基线风险层级进行分层的必要性待专家确认。",
                "分层变量、切点和交互检验必须在分析前预先指定。");
        List<String> subgroup = List.of(
                "亚组分析仅作为预先指定的探索性分析，控制数量并报告交互效应。",
                "样本不足或事件数不足时不输出不稳定的亚组结论。");
        List<String> sensitivity = sensitivityAnalyses(studyType);
        List<String> missing = List.of(
                "逐变量报告缺失数量、比例和缺失模式，并比较完整与缺失记录的基线特征。",
                "完整病例分析、多重插补或其他方法的适用性由缺失机制、比例和变量类型决定。",
                "主分析与缺失数据敏感性分析必须分别报告，不把插补值描述为真实观测。");
        List<String> multiplicity = List.of(
                "主要终点仅保留医生已确认的一项；次要终点和亚组的多重比较策略待确认。",
                "如不调整多重性，必须将次要和探索性结果明确标记并谨慎解释。");
        List<String> diagnostics = diagnostics(studyType);
        List<String> effectMeasures = effectMeasures(studyType);
        String confidenceInterval = "报告效应量及双侧置信区间；置信水平、显著性水平和检验方向"
                + "必须由统计学专家在分析前确认，当前不填入假定数值。";
        List<String> software = List.of(
                "R、SAS 或 Stata 均可作为候选；由医院许可、可复现要求和统计团队能力确定。",
                "正式分析必须记录软件名称、版本、包版本、运行日期和可复现脚本位置。");
        List<String> issues = issues(studyType);
        String sectionContent = renderSection(
                descriptive, primary, secondary, covariates, confounders,
                stratified, subgroup, sensitivity, missing, multiplicity,
                diagnostics, effectMeasures, confidenceInterval, parameters,
                software, issues);
        ResearchProtocolModels.ProtocolSection previous = protocol.sections().stream()
                .filter(value -> "STATISTICAL_ANALYSIS".equals(value.sectionCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("研究方案缺少统计分析章节"));
        if (previous.versionNo() != 1) {
            throw new IllegalStateException("统计分析章节已被修改，不能静默覆盖");
        }
        var statisticalSection = new ResearchProtocolModels.ProtocolSection(
                previous.sectionId(), previous.sectionCode(), previous.title(),
                previous.sortOrder(), 2, sectionContent, "MARKDOWN",
                "AGENT_DETERMINISTIC", "NEEDS_EXPERT_REVIEW",
                List.of("STEP12", "STEP13"),
                List.of(
                        "确认主要终点变量类型与效应度量",
                        "确认协变量、混杂因素、缺失数据和多重比较策略",
                        "提供并审核样本量估算参数后再调用确定性计算工具"));
        var result = new StatisticalAnalysisModels.StatisticalDraft(
                RESULT_SCHEMA_VERSION,
                draftId,
                protocol.protocolId(),
                generatedAt,
                studyType,
                primaryOutcome,
                "NEEDS_EXPERT_CONFIRMATION",
                descriptive,
                primary,
                secondary,
                covariates,
                confounders,
                stratified,
                subgroup,
                sensitivity,
                missing,
                multiplicity,
                diagnostics,
                effectMeasures,
                confidenceInterval,
                parameters,
                software,
                issues,
                statisticalSection,
                inputHash,
                GENERATOR_VERSION,
                List.of(
                        "该结果是统计分析计划草案，不承诺所列方法适用于最终数据，必须由统计学专家审核。",
                        "主要终点变量类型、分布、事件时间结构和数据质量尚未确认，因此只提供条件化候选方法。",
                        "所有样本量参数均保持待输入状态；本步骤不计算、不猜测也不承诺最终样本量。",
                        "不得根据观察到的显著性、效应方向或模型收敛情况静默改写预先指定分析。"));
        repository.save(
                new StatisticalAnalysisDraftRepository.DraftData(
                        draftId,
                        hospitalId,
                        projectId,
                        agentTaskId,
                        protocol.protocolId(),
                        "DRAFT",
                        studyType.name(),
                        primaryOutcome,
                        result.outcomeTypeStatus(),
                        parameters.size(),
                        inputHash,
                        GENERATOR_VERSION,
                        write(result),
                        generatedAt),
                parameters,
                statisticalSection);
        return result;
    }

    public ResearchProtocolModels.ProtocolDraft applyToProtocol(
            ResearchProtocolModels.ProtocolDraft protocol,
            StatisticalAnalysisModels.StatisticalDraft statisticalDraft) {
        List<ResearchProtocolModels.ProtocolSection> sections = protocol.sections().stream()
                .map(value -> "STATISTICAL_ANALYSIS".equals(value.sectionCode())
                        ? statisticalDraft.statisticalSectionVersion() : value)
                .toList();
        LinkedHashSet<String> issues = new LinkedHashSet<>(protocol.issuesToConfirm());
        issues.addAll(statisticalDraft.issuesToConfirm());
        return new ResearchProtocolModels.ProtocolDraft(
                protocol.schemaVersion(), protocol.protocolId(), protocol.generatedAt(),
                protocol.studyType(), protocol.title(), sections, List.copyOf(issues),
                protocol.inputSha256(), protocol.generatorVersion(), protocol.limitations());
    }

    private List<String> descriptiveAnalysis(StudyType studyType) {
        List<String> values = new ArrayList<>(List.of(
                "连续变量按分布报告例数、均值与标准差或中位数与四分位数；分类变量报告频数和比例。",
                "按主要暴露/病例对照状态展示基线特征，同时报告缺失数量；不把基线 P 值作为可比性证明。"));
        if (studyType == StudyType.COHORT) {
            values.add("报告随访时长、观察人时、失访和删失情况。");
        } else if (studyType == StudyType.CASE_CONTROL) {
            values.add("分别报告病例与对照的来源、匹配状态和索引日期分布。");
        } else {
            values.add("说明抽样框、抽样权重及调查/数据截面日期。");
        }
        return List.copyOf(values);
    }

    private List<String> primaryAnalysis(StudyType studyType) {
        return switch (studyType) {
            case COHORT -> List.of(
                    "若主要终点为连续变化量，候选为线性模型或适合重复测量结构的模型。",
                    "若为二分类结局，候选为风险比/风险差模型；若具有事件时间，候选为生存分析模型。",
                    "必须明确共同时间零点、随访窗口、删失规则和时间相关暴露处理。");
            case CROSS_SECTIONAL -> List.of(
                    "连续终点候选为线性模型；二分类终点候选为患病率比、患病率差或比值比模型。",
                    "如采用复杂抽样，分析必须纳入权重、分层和聚类设计。",
                    "仅解释同一截面的统计学关联，不推断时间顺序或因果关系。");
            case CASE_CONTROL -> List.of(
                    "主要效应度量候选为比值比及其置信区间。",
                    "未匹配设计候选为非条件 Logistic 回归；匹配设计候选为条件 Logistic 回归。",
                    "模型必须与病例/对照抽样和匹配方案一致，不直接估计普通人群风险。");
        };
    }

    private List<String> sensitivityAnalyses(StudyType studyType) {
        List<String> common = new ArrayList<>(List.of(
                "使用替代变量定义、时间窗或协变量集合重复主要分析。",
                "比较完整病例与预先确认的缺失数据处理方法。",
                "评估极端值、模型设定和未测量混杂对结论稳健性的影响。"));
        switch (studyType) {
            case COHORT -> common.add("评估时间零点、失访、删失和时间相关偏倚的替代处理。");
            case CROSS_SECTIONAL -> common.add("评估抽样权重、聚类和不同患病定义的影响。");
            case CASE_CONTROL -> common.add("评估不同对照定义、匹配处理和暴露误分类的影响。");
        }
        return List.copyOf(common);
    }

    private List<String> diagnostics(StudyType studyType) {
        List<String> common = new ArrayList<>(List.of(
                "检查模型收敛、残差、异常/高影响观测、多重共线性及函数形式。",
                "模型诊断失败时记录原因和预先定义的替代方案，不静默更换模型。"));
        switch (studyType) {
            case COHORT -> common.add("事件时间模型需检查比例风险等适用假设。");
            case CROSS_SECTIONAL -> common.add("复杂抽样模型需检查权重和聚类设定。");
            case CASE_CONTROL -> common.add("检查匹配结构、稀疏数据和完全/准完全分离。");
        }
        return List.copyOf(common);
    }

    private List<String> effectMeasures(StudyType studyType) {
        return switch (studyType) {
            case COHORT -> List.of(
                    "均值差/变化量差", "风险差", "风险比（RR）",
                    "发生率比", "风险函数比（HR）");
            case CROSS_SECTIONAL -> List.of("均值差", "患病率差", "患病率比", "比值比");
            case CASE_CONTROL -> List.of("比值比", "匹配比值比");
        };
    }

    private List<StatisticalAnalysisModels.SampleSizeParameter> sampleSizeParameters(
            StudyType studyType) {
        List<StatisticalAnalysisModels.SampleSizeParameter> values = new ArrayList<>();
        addParameter(values, "OUTCOME_TYPE", "主要终点变量类型", null,
                "决定使用连续、二分类、计数或事件时间的确定性公式。");
        addParameter(values, "TARGET_EFFECT", "具有临床意义的目标效应量", null,
                "必须由临床意义、既有证据或预试验提供，不由 Agent 猜测。");
        addParameter(values, "ALPHA", "显著性水平", null,
                "由统计学专家确认单侧/双侧及数值。");
        addParameter(values, "POWER", "检验效能", null,
                "由统计学专家确认目标把握度。");
        switch (studyType) {
            case COHORT -> {
                addParameter(values, "BASELINE_EVENT_OR_SD",
                        "对照组事件率、发生率或连续终点标准差", null,
                        "按最终终点类型选择对应参数。");
                addParameter(values, "EXPOSURE_ALLOCATION_RATIO",
                        "暴露组与对照组比例", null, "反映可获得数据中的组别分布。");
                addParameter(values, "FOLLOW_UP_DURATION",
                        "计划随访时长", "时间", "事件时间或发生率分析需要。");
                addParameter(values, "LOSS_TO_FOLLOW_UP",
                        "预计失访/删失比例", "比例", "用于调整可分析样本量。");
            }
            case CROSS_SECTIONAL -> {
                addParameter(values, "EXPECTED_PREVALENCE_OR_SD",
                        "预期患病率或连续终点标准差", null,
                        "按患病率估计或关联分析目标选择。");
                addParameter(values, "PRECISION_OR_GROUP_RATIO",
                        "允许误差或暴露组比例", null,
                        "按描述性或比较性主要目标选择。");
                addParameter(values, "DESIGN_EFFECT",
                        "复杂抽样设计效应", null,
                        "存在聚类、分层或加权抽样时需要。");
                addParameter(values, "NON_RESPONSE_RATE",
                        "预计无应答/不可用记录比例", "比例", "用于调整初始样本量。");
            }
            case CASE_CONTROL -> {
                addParameter(values, "CONTROL_EXPOSURE_PREVALENCE",
                        "对照组暴露率", "比例", "病例对照样本量计算的基础参数。");
                addParameter(values, "CASE_CONTROL_RATIO",
                        "病例与对照比例", null, "需与可获得病例数和抽样方案一致。");
                addParameter(values, "MATCHING_ADJUSTMENT",
                        "匹配相关或设计修正参数", null,
                        "匹配设计需使用经验证的相应公式。");
                addParameter(values, "UNUSABLE_RECORD_RATE",
                        "预计不可用记录比例", "比例", "用于调整可分析样本量。");
            }
        }
        return List.copyOf(values);
    }

    private void addParameter(
            List<StatisticalAnalysisModels.SampleSizeParameter> values,
            String code,
            String label,
            String unit,
            String rationale) {
        values.add(new StatisticalAnalysisModels.SampleSizeParameter(
                code, label, true, "MISSING_NEEDS_INPUT", null, unit, rationale));
    }

    private List<String> issues(StudyType studyType) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add("确认主要终点的变量类型、测量时间和效应度量");
        values.add("确认次要终点、协变量、混杂因素和效应修饰因素");
        values.add("确认主要分析集、缺失数据、多重比较、分层和亚组策略");
        values.addAll(designSpecificIssues(studyType));
        values.add("提供全部样本量参数后，才能调用经过验证的确定性计算函数");
        values.add("由统计学专家审核软件、模型、诊断和结果解释规则");
        return List.copyOf(values);
    }

    private List<String> designSpecificIssues(StudyType studyType) {
        return switch (studyType) {
            case COHORT -> List.of("确认共同时间零点、随访、删失和时间相关暴露处理");
            case CROSS_SECTIONAL -> List.of("确认抽样权重、分层、聚类和患病率效应度量");
            case CASE_CONTROL -> List.of("确认匹配状态、对照抽样和条件/非条件模型");
        };
    }

    private String renderSection(
            List<String> descriptive,
            List<String> primary,
            List<String> secondary,
            List<String> covariates,
            List<String> confounders,
            List<String> stratified,
            List<String> subgroup,
            List<String> sensitivity,
            List<String> missing,
            List<String> multiplicity,
            List<String> diagnostics,
            List<String> effects,
            String confidenceInterval,
            List<StatisticalAnalysisModels.SampleSizeParameter> parameters,
            List<String> software,
            List<String> issues) {
        StringBuilder value = new StringBuilder();
        append(value, "描述性统计", descriptive);
        append(value, "主要终点候选分析", primary);
        append(value, "次要终点", secondary);
        append(value, "协变量与潜在混杂", concat(covariates, confounders));
        append(value, "分层与亚组分析", concat(stratified, subgroup));
        append(value, "敏感性分析", sensitivity);
        append(value, "缺失数据", missing);
        append(value, "多重比较", multiplicity);
        append(value, "模型诊断", diagnostics);
        append(value, "候选效应量", effects);
        value.append("\n## 置信区间\n- ").append(confidenceInterval).append('\n');
        value.append("\n## 样本量参数（全部待提供，不执行计算）\n");
        parameters.forEach(parameter -> value.append("- ")
                .append(parameter.label()).append("：")
                .append(parameter.valueStatus()).append("；")
                .append(parameter.rationale()).append('\n'));
        append(value, "统计软件", software);
        append(value, "待统计学专家确认", issues);
        return value.toString().strip();
    }

    private void append(StringBuilder value, String heading, List<String> lines) {
        value.append("\n## ").append(heading).append('\n');
        lines.forEach(line -> value.append("- ").append(line).append('\n'));
    }

    private List<String> concat(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        result.addAll(right);
        return List.copyOf(result);
    }

    private void requireInputs(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            ResearchProtocolModels.ProtocolDraft protocol,
            ObservationalDesignRecommendationModels.Recommendation design) {
        if (hospitalId == null || projectId == null || agentTaskId == null
                || protocol == null || design == null) {
            throw new IllegalArgumentException("统计分析草案输入不完整");
        }
        if (!ResearchProtocolGenerationService.RESULT_SCHEMA_VERSION.equals(
                protocol.schemaVersion())
                || protocol.sections() == null || protocol.sections().size() != 18) {
            throw new IllegalStateException("研究方案章节未完整生成");
        }
        if (!ObservationalDesignRecommendationService.CONFIRMED.equals(
                design.confirmationStatus())
                || design.confirmedStudyType() == null
                || design.confirmedPrimaryOutcome() == null
                || design.confirmedPrimaryOutcome().isBlank()
                || !design.protocolGenerationAuthorized()
                || design.confirmedStudyType() != protocol.studyType()) {
            throw new IllegalStateException("统计分析草案缺少已确认的研究设计或主要终点");
        }
    }

    private byte[] writeBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException("统计分析草案输入序列化失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("统计分析草案结果序列化失败", exception);
        }
    }

    private StatisticalAnalysisModels.StatisticalDraft read(String value) {
        try {
            return json.readValue(value, StatisticalAnalysisModels.StatisticalDraft.class);
        } catch (Exception exception) {
            throw new IllegalStateException("已持久化统计分析草案结果损坏", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("统计分析草案输入哈希失败", exception);
        }
    }
}
