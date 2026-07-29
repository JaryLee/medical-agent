package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisModels;
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
public class ResearchProtocolGenerationService {
    public static final String RESULT_SCHEMA_VERSION = "research-protocol-draft/v1";
    public static final String GENERATOR_VERSION =
            "deterministic-observational-protocol/v1";
    private static final String ORIGIN = "AGENT_DETERMINISTIC";

    private final ResearchProtocolRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public ResearchProtocolGenerationService(
            ResearchProtocolRepository repository,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    public ResearchProtocolModels.ProtocolDraft execute(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            AnalysisResult analysis,
            PecoDefinition peco,
            ObservationalDesignRecommendationModels.Recommendation design,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        requireInputs(
                hospitalId, projectId, agentTaskId, analysis, peco, design,
                similarResearch);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("analysis", analysis);
        snapshot.put("peco", peco);
        snapshot.put("confirmedObservationalDesign", design);
        snapshot.put("similarResearchAnalysis", similarResearch);
        String inputHash = sha256(writeBytes(snapshot));
        var existing = repository.findByAgentTask(hospitalId, agentTaskId);
        if (existing.isPresent()) {
            if (!inputHash.equals(existing.get().inputSha256())) {
                throw new IllegalStateException(
                        "同一 Agent 任务已存在输入不一致的研究方案");
            }
            return readDraft(existing.get().resultJson());
        }
        UUID protocolId = UUID.randomUUID();
        Instant generatedAt = clock.instant();
        String title = protocolTitle(
                peco, design.confirmedStudyType(), design.confirmedPrimaryOutcome());
        List<String> protocolIssues = protocolIssues(design, similarResearch);
        List<ResearchProtocolModels.ProtocolSection> sections = sections(
                analysis, peco, design, similarResearch, title);
        var draft = new ResearchProtocolModels.ProtocolDraft(
                RESULT_SCHEMA_VERSION,
                protocolId,
                generatedAt,
                design.confirmedStudyType(),
                title,
                sections,
                protocolIssues,
                inputHash,
                GENERATOR_VERSION,
                List.of(
                        "本草案由版本化确定性模板生成，只用于观察性研究方案起草，不构成诊疗、伦理、统计或科研管理结论。",
                        "统计分析章节仅列出 STEP14 所需参数，不自动计算样本量，也不生成显著性或因果结论。",
                        "参考文献仅来自当前已核验检索记录；摘要级证据不能替代全文审阅。",
                        "所有章节均为初始版本，提交专家审核前必须由医生、流行病学和统计学专家逐章复核。"));
        repository.save(
                new ResearchProtocolRepository.ProtocolData(
                        protocolId,
                        hospitalId,
                        projectId,
                        agentTaskId,
                        "DRAFT",
                        design.confirmedStudyType().name(),
                        title,
                        RESULT_SCHEMA_VERSION,
                        GENERATOR_VERSION,
                        inputHash,
                        write(protocolIssues),
                        write(draft),
                        generatedAt),
                sections);
        return draft;
    }

    private List<ResearchProtocolModels.ProtocolSection> sections(
            AnalysisResult analysis,
            PecoDefinition peco,
            ObservationalDesignRecommendationModels.Recommendation design,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch,
            String title) {
        List<ResearchProtocolModels.ProtocolSection> values = new ArrayList<>();
        var profile = analysis.profile();
        List<String> evidenceSources = evidenceSourceIdentifiers(similarResearch);
        String primaryOutcome = design.confirmedPrimaryOutcome();
        StudyType studyType = design.confirmedStudyType();
        String evidenceStatus = evidenceSources.isEmpty()
                ? "NEEDS_EXPERT_REVIEW" : "ABSTRACT_ONLY";

        add(values, "TITLE", "课题名称", title,
                "DOCTOR_CONFIRMED_INPUT", List.of("PECO", "STEP12"), List.of());
        add(values, "ABSTRACT", "摘要",
                "本研究拟在" + known(profile.setting(), "待确认研究场景")
                        + "开展" + studyTypeLabel(studyType)
                        + "，研究对象为" + peco.population()
                        + "，主要暴露为" + peco.exposure()
                        + "，对照为" + known(peco.comparator(), "待确认对照")
                        + "，主要终点为" + primaryOutcome
                        + "。本摘要为结构化草案，样本量、统计方法和伦理审批状态将在后续章节及专家审核中补充。",
                "DOCTOR_CONFIRMED_INPUT", List.of("PECO", "STEP12"),
                List.of("补充研究时间范围、数据来源、样本量依据和统计方法后更新摘要"));
        add(values, "BACKGROUND", "研究背景",
                "当前临床问题为“" + known(profile.clinicalProblem(), peco.researchQuestion())
                        + "”。拟围绕“" + peco.researchQuestion()
                        + "”形成观察性研究方案。当前证据仅依据已记录的 PubMed、ClinicalTrials.gov "
                        + "和 Crossref 检索及核验结果，不能据此形成诊疗建议或完整证据综述。",
                evidenceStatus, evidenceSources,
                List.of("由医学专家补充经全文核验的疾病负担、临床意义和现有证据"));
        add(values, "RESEARCH_STATUS", "国内外研究现状",
                researchStatus(similarResearch),
                evidenceStatus, evidenceSources,
                List.of("当前未覆盖 CNKI、万方、维普、Embase、Web of Science 和灰色文献"));
        add(values, "RESEARCH_GAP", "潜在研究空白",
                researchGaps(similarResearch),
                evidenceStatus, evidenceSources,
                List.of("潜在空白不等于创新性或优先权证明，必须由专家复核"));
        add(values, "OBJECTIVES", "研究目标",
                "- 主要目标：评估" + peco.exposure() + "与" + primaryOutcome
                        + "之间的统计学关联。\n"
                        + "- 次要目标：待医生和统计学专家依据可用变量与临床价值确认。",
                "DOCTOR_CONFIRMED_INPUT", List.of("PECO", "STEP12"),
                List.of("确认次要终点及探索性目标"));
        add(values, "HYPOTHESIS", "研究假设",
                "探索性研究假设：" + peco.exposure() + "与" + primaryOutcome
                        + "存在可检验的统计学关联。该表述不预设方向、效应量、显著性或因果关系。",
                "NEEDS_EXPERT_REVIEW", List.of("PECO"),
                List.of("由统计学专家确认零假设、备择假设及效应度量"));
        add(values, "STUDY_DESIGN", "研究设计",
                designContent(studyType, profile.timeFrame()),
                "DOCTOR_CONFIRMED_INPUT", List.of("STEP12"),
                designIssues(studyType));
        add(values, "PARTICIPANTS", "研究对象",
                "- 目标人群：" + peco.population() + "\n"
                        + "- 研究场景：" + known(profile.setting(), "待确认") + "\n"
                        + "- 观察时间：" + known(profile.timeFrame(), "待确认") + "\n"
                        + "- 抽样框、病例来源和代表性：待确认。",
                "DOCTOR_CONFIRMED_INPUT", List.of("PECO"),
                List.of("确认病例来源、研究期间、抽样方法和可推广人群"));
        add(values, "ELIGIBILITY", "纳入与排除标准",
                "- 纳入标准草案：符合“" + peco.population() + "”定义，并在研究期间具有主要暴露、"
                        + "对照及主要终点所需记录。\n"
                        + "- 排除标准草案：关键时间点、主要暴露或主要终点无法判定；重复或无法完成"
                        + "质量核验的记录。\n"
                        + "- 其他疾病、用药、年龄和随访限制：待临床专家确认。",
                "NEEDS_EXPERT_REVIEW", List.of("PECO"),
                List.of("逐条定义可执行的纳入/排除标准，避免按结局选择研究对象"));
        add(values, "OUTCOMES_VARIABLES", "变量和终点",
                "- 主要终点：" + primaryOutcome + "（已人工确认）。\n"
                        + "- 主要暴露：" + peco.exposure() + "。\n"
                        + "- 对照：" + known(peco.comparator(), "待确认") + "。\n"
                        + "- 次要终点、混杂因素、效应修饰因素、变量单位、时间窗及缺失值规则：待定义。",
                "DOCTOR_CONFIRMED_INPUT", List.of("PECO", "STEP12"),
                List.of("建立变量字典；确认次要终点、协变量、单位、来源、时间窗和缺失值规则"));
        add(values, "DATA_COLLECTION", "数据收集",
                "拟从" + known(profile.setting(), "待确认数据来源")
                        + "获取研究所需数据。数据提取应使用预先定义的数据字典和时间窗，记录来源系统、"
                        + "提取版本、去重规则、质量检查及缺失原因；本步骤不接入或分析真实患者明细。",
                "NEEDS_EXPERT_REVIEW", List.of("PECO"),
                List.of("确认数据拥有者、提取范围、质量规则、去重规则和访问权限"));
        add(values, "STATISTICAL_ANALYSIS", "统计分析",
                "本章节将在 STEP14 生成统计分析草案。进入 STEP14 前需确认：效应度量、主要分析集、"
                        + "混杂因素处理、缺失数据策略、敏感性分析、亚组分析边界，以及样本量估算所需"
                        + "事件率/标准差、预期效应量、显著性水平和把握度。系统不会自动猜测样本量。",
                "NEEDS_EXPERT_REVIEW", List.of("STEP12"),
                List.of("等待 STEP14；由统计学专家提供样本量参数并确认分析方法"));
        add(values, "BIAS_CONTROL", "偏倚与混杂控制",
                biasContent(studyType, design),
                "NEEDS_EXPERT_REVIEW", List.of("STEP12"),
                List.of("由流行病学和统计学专家确认偏倚识别、混杂控制及敏感性分析"));
        add(values, "ETHICS_DATA_SECURITY", "伦理和数据安全",
                "研究开始前应完成适用的伦理审查、知情同意或豁免判断及科研管理审批。数据处理遵循"
                        + "最小必要、医院隔离、访问控制、去标识化、审计和受控导出原则。本草案不构成"
                        + "伦理批准，也不得自动提交伦理或基金申请。",
                "NEEDS_EXPERT_REVIEW", List.of(),
                List.of("确认伦理审查路径、知情同意/豁免、数据保存期限和访问责任人"));
        add(values, "SCHEDULE", "研究进度",
                "- 方案与变量字典确认：待排期。\n"
                        + "- 伦理与数据审批：待排期。\n"
                        + "- 数据提取和质量核验：待排期。\n"
                        + "- 统计分析、专家审核和报告：待排期。",
                "NEEDS_EXPERT_REVIEW", List.of(),
                List.of("由课题负责人填写实际里程碑、责任人和日期"));
        add(values, "EXPECTED_RESULTS", "预期成果",
                "预期形成可复核的数据字典、统计分析记录和观察性研究报告草案。不得预设主要终点方向、"
                        + "统计显著性、临床获益、创新性或论文发表结果。",
                "NEEDS_EXPERT_REVIEW", List.of(),
                List.of("由课题负责人确认成果形式及科研管理要求"));
        add(values, "REFERENCES", "参考文献",
                references(similarResearch),
                evidenceSources.isEmpty() ? "NEEDS_EXPERT_REVIEW" : "VERIFIED_METADATA",
                evidenceSources,
                List.of("提交专家审核前逐篇完成全文审阅和 STEP15 主张—引用验证"));
        return List.copyOf(values);
    }

    private void add(
            List<ResearchProtocolModels.ProtocolSection> values,
            String code,
            String title,
            String content,
            String evidenceStatus,
            List<String> sources,
            List<String> issues) {
        values.add(new ResearchProtocolModels.ProtocolSection(
                UUID.randomUUID(), code, title, values.size() + 1, 1,
                content, "MARKDOWN", ORIGIN, evidenceStatus,
                List.copyOf(sources), List.copyOf(issues)));
    }

    private String researchStatus(
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        return "当前已分析 " + similarResearch.analyzedSourceCount()
                + " 个核验后来源，其中高度相似 "
                + similarResearch.highSimilarityCount() + " 个、中度相似 "
                + similarResearch.moderateSimilarityCount() + " 个、低度相似 "
                + similarResearch.lowSimilarityCount() + " 个。\n\n"
                + similarResearch.conclusion();
    }

    private String researchGaps(
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        if (similarResearch.potentialResearchGaps().isEmpty()) {
            return "当前检索范围未形成可直接采用的潜在研究空白，需扩展检索并由专家判断。";
        }
        return similarResearch.potentialResearchGaps().stream()
                .map(value -> "- " + value.statement() + "（依据：" + value.basis() + "）")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String references(
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        List<SimilarResearchAnalysisModels.SimilarResearch> references =
                similarResearch.similarResearch().stream()
                        .filter(value -> "PUBMED_ARTICLE".equals(value.sourceType()))
                        .toList();
        if (references.isEmpty()) {
            return "当前没有可列入的已核验 PubMed 引文；不得由模型或模板生成 PMID/DOI。";
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < references.size(); index++) {
            var value = references.get(index);
            StringBuilder line = new StringBuilder()
                    .append(index + 1).append(". ")
                    .append(value.title())
                    .append(" PMID:").append(value.pmid());
            if (value.doi() != null && !value.doi().isBlank()) {
                line.append(" DOI:").append(value.doi());
            }
            line.append(" [").append(value.evidenceScope()).append("]");
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    private List<String> evidenceSourceIdentifiers(
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        similarResearch.similarResearch().stream()
                .filter(value -> "PUBMED_ARTICLE".equals(value.sourceType()))
                .forEach(value -> values.add("PMID:" + value.pmid()));
        return List.copyOf(values);
    }

    private String designContent(StudyType type, String timeFrame) {
        return switch (type) {
            case COHORT -> "采用队列研究设计，按主要暴露状态定义研究组和对照组，从共同时间零点开始，"
                    + "在" + known(timeFrame, "待确认观察期") + "内观察主要终点。需明确回顾性或前瞻性、"
                    + "入组时点、随访起止、结局判定及失访处理。";
            case CROSS_SECTIONAL -> "采用横断面研究设计，在预先定义的调查或数据截面内同时测量暴露与"
                    + "主要终点，用于估计分布和统计学关联，不用于确定时间顺序或因果关系。";
            case CASE_CONTROL -> "采用病例对照研究设计，依据主要终点定义病例，并从产生病例的同一"
                    + "源人群选取对照，回溯评估主要暴露。需预先定义对照抽样、匹配及索引日期。";
        };
    }

    private List<String> designIssues(StudyType type) {
        return switch (type) {
            case COHORT -> List.of("确认回顾性/前瞻性、共同时间零点、随访窗口和失访规则");
            case CROSS_SECTIONAL -> List.of("确认调查/数据截面、抽样框和患病率或关联效应度量");
            case CASE_CONTROL -> List.of("确认病例定义、源人群、对照抽样、匹配因素和索引日期");
        };
    }

    private String biasContent(
            StudyType type,
            ObservationalDesignRecommendationModels.Recommendation design) {
        List<String> risks = design.alternatives().stream()
                .filter(value -> value.studyType() == type)
                .findFirst()
                .map(ObservationalDesignRecommendationModels.DesignAlternative::biasRisks)
                .orElse(List.of());
        String listed = risks.isEmpty()
                ? "待专家识别" : String.join("、", risks);
        return "当前设计需重点考虑：" + listed
                + "。应预先定义混杂因素、选择机制、信息误差、时间相关偏倚和缺失数据的控制策略；"
                + "具体方法将在 STEP14 统计草案及专家审核中确认。";
    }

    private List<String> protocolIssues(
            ObservationalDesignRecommendationModels.Recommendation design,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        issues.addAll(design.unresolvedItems());
        issues.add("确认次要终点、变量字典、纳入/排除标准和数据收集规则");
        issues.add("由统计学专家在 STEP14 确认分析方法及样本量估算参数");
        issues.add("完成伦理、隐私、数据安全和科研管理审核");
        if (similarResearch.highSimilarityCount() > 0) {
            issues.add("复核高度相似研究与本研究的实质差异");
        }
        issues.add("在 STEP15 完成事实主张与引用依据验证");
        return List.copyOf(issues);
    }

    private String protocolTitle(
            PecoDefinition peco, StudyType studyType, String outcome) {
        String title = peco.population() + "中" + peco.exposure()
                + "与" + outcome + "关联的" + studyTypeLabel(studyType);
        return title.length() <= 500 ? title : title.substring(0, 500);
    }

    private String studyTypeLabel(StudyType type) {
        return switch (type) {
            case COHORT -> "队列研究";
            case CROSS_SECTIONAL -> "横断面研究";
            case CASE_CONTROL -> "病例对照研究";
        };
    }

    private String known(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void requireInputs(
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            AnalysisResult analysis,
            PecoDefinition peco,
            ObservationalDesignRecommendationModels.Recommendation design,
            SimilarResearchAnalysisModels.AnalysisResult similarResearch) {
        if (hospitalId == null || projectId == null || agentTaskId == null
                || analysis == null || analysis.profile() == null || peco == null
                || design == null || similarResearch == null) {
            throw new IllegalArgumentException("研究方案生成输入不完整");
        }
        if (!ObservationalDesignRecommendationService.CONFIRMED.equals(
                design.confirmationStatus())
                || design.confirmedStudyType() == null
                || design.confirmedPrimaryOutcome() == null
                || design.confirmedPrimaryOutcome().isBlank()
                || !design.protocolGenerationAuthorized()) {
            throw new IllegalStateException(
                    "研究类型、主要终点和正式方案生成授权尚未完成");
        }
        if (peco.studyType() == null || peco.population() == null
                || peco.population().isBlank() || peco.exposure() == null
                || peco.exposure().isBlank()) {
            throw new IllegalArgumentException("已确认 PECO 不完整");
        }
    }

    private byte[] writeBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException("研究方案输入序列化失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("研究方案结果序列化失败", exception);
        }
    }

    private ResearchProtocolModels.ProtocolDraft readDraft(String value) {
        try {
            return json.readValue(value, ResearchProtocolModels.ProtocolDraft.class);
        } catch (Exception exception) {
            throw new IllegalStateException("已持久化研究方案结果损坏", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("研究方案输入哈希失败", exception);
        }
    }
}
