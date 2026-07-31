package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.ProtocolModelGovernanceRepository;
import com.jarylee.medicalagent.agent.ProtocolSectionCandidateValidator;
import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.agent.model.ProtocolSectionModel;
import com.jarylee.medicalagent.agent.model.ObservationalDesignModel;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.ModelCallAuditService;
import com.jarylee.medicalagent.workflow.ResearchProtocolRepository;
import com.jarylee.medicalagent.workflow.ObservationalDesignRecommendationModels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

@Service
public class WorkspaceModelGovernanceService {
    private static final Set<String> REVIEW_SEVERITIES =
            Set.of("NONE", "LOW", "MEDIUM", "HIGH", "BLOCKING");
    private static final Set<String> ISSUE_SEVERITIES =
            Set.of("LOW", "MEDIUM", "HIGH", "BLOCKING");
    private static final Pattern INTERNAL_IDENTIFIER = Pattern.compile(
            "(?i)(\\bSTEP[_-]?\\d+\\b|[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})");
    private static final Pattern EVIDENCE_IDENTIFIER = Pattern.compile(
            "(?i)(?:\\bPMID\\s*[:：]?\\s*\\d{5,10}\\b"
                    + "|\\bNCT\\d{8}\\b"
                    + "|\\b10\\.\\d{4,9}/[-._;()/:A-Z0-9]+\\b)");

    private final WorkspaceReadModelService readModels;
    private final WorkspaceArtifactReadService artifacts;
    private final WorkspaceProtocolRevisionService revisions;
    private final ResearchProtocolRepository protocols;
    private final ProtocolModelGovernanceRepository repository;
    private final WorkspaceModelGovernancePersistenceService persistence;
    private final ModelRouter models;
    private final ModelCallAuditService modelCalls;
    private final PromptTemplateRegistry prompts;
    private final ProtocolSectionCandidateValidator validator;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkspaceModelGovernanceService(
            WorkspaceReadModelService readModels,
            WorkspaceArtifactReadService artifacts,
            WorkspaceProtocolRevisionService revisions,
            ResearchProtocolRepository protocols,
            ProtocolModelGovernanceRepository repository,
            WorkspaceModelGovernancePersistenceService persistence,
            ModelRouter models,
            ModelCallAuditService modelCalls,
            PromptTemplateRegistry prompts,
            ProtocolSectionCandidateValidator validator,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.readModels = readModels;
        this.artifacts = artifacts;
        this.revisions = revisions;
        this.protocols = protocols;
        this.repository = repository;
        this.persistence = persistence;
        this.models = models;
        this.modelCalls = modelCalls;
        this.prompts = prompts;
        this.validator = validator;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public CandidateView generateCandidate(
            WorkspaceReadModelService.WorkspaceContext context,
            String sectionKey) {
        requireEditable(context);
        Source source = source(context, sectionKey);
        var route = models.resolve(LogicalModelType.RESEARCH_STANDARD);
        var prompt = prompts.require("PROTOCOL_SECTION_GENERATION");
        List<String> sourceIdentifiers = readList(
                source.version().sourceIdentifiersJson());
        Set<String> allowed = validator.normalizedAllowed(sourceIdentifiers);
        var request = new ProtocolSectionModel.GenerationRequest(
                ProtocolSectionModel.GENERATION_INPUT_SCHEMA,
                source.protocol().studyType(),
                source.section().sectionCode(),
                source.section().title(),
                source.section().currentVersionNo(),
                source.version().content(),
                List.of(
                        "研究类型：" + source.protocol().studyType(),
                        "课题名称：" + source.protocol().title(),
                        "当前章节：" + source.section().title()),
                allowed.stream().sorted().toList(),
                List.of(
                        "仅供科研设计讨论，未经伦理和科研管理审批",
                        "不得生成因果、诊疗、显著性或正式批准结论"));
        String controlledInput = write(request);
        var invocation = modelCalls.invokeStructured(
                source.task(),
                source.section().currentVersionNo(),
                prompt,
                route,
                ProtocolSectionModel.GENERATION_INPUT_SCHEMA,
                ProtocolSectionModel.GENERATION_OUTPUT_SCHEMA,
                controlledInput,
                () -> route.model().generateProtocolSection(request, prompt));
        var validation = validator.validate(
                source.section().sectionCode(), allowed, invocation.output());
        Instant now = clock.instant();
        UUID candidateId = UUID.randomUUID();
        var candidate = new ProtocolModelGovernanceRepository.CandidateData(
                candidateId,
                context.actor().hospitalId(),
                context.project().id(),
                source.task().id(),
                source.protocol().id(),
                source.section().id(),
                source.section().sectionCode(),
                source.section().currentVersionNo(),
                invocation.modelCallId(),
                prompt.version(),
                validation.content(),
                sha256(validation.content()),
                write(validation.usedEvidenceIdentifiers()),
                sha256(write(allowed.stream().sorted().toList())),
                write(validation.issuesToConfirm()),
                write(validation),
                "VALIDATED",
                now,
                null,
                null,
                null,
                0);
        persistence.saveCandidate(candidate);
        audit.record(
                context.actor(),
                "WORKSPACE_PROTOCOL_MODEL_CANDIDATE_GENERATED",
                "RESEARCH_PROJECT",
                context.project().projectKey());
        return view(context.project().projectKey(), candidate);
    }

    public ReviewView reviewCandidate(
            WorkspaceReadModelService.WorkspaceContext context,
            String candidateKey) {
        requireEditable(context);
        var candidate = resolveCandidate(context, candidateKey);
        if (!"VALIDATED".equals(candidate.status())) {
            throw BusinessException.conflict(
                    "MODEL_CANDIDATE_NOT_REVIEWABLE",
                    "当前模型章节候选不可复核");
        }
        if (repository.findReviewByCandidate(
                context.actor().hospitalId(), candidate.id()).isPresent()) {
            throw BusinessException.conflict(
                    "MODEL_CANDIDATE_ALREADY_REVIEWED",
                    "当前模型章节候选已经完成辅助复核");
        }
        models.requireIndependentReview(LogicalModelType.RESEARCH_STANDARD);
        var route = models.resolve(LogicalModelType.RESEARCH_REVIEW);
        var prompt = prompts.require("PROTOCOL_SECTION_REVIEW");
        var request = new ProtocolSectionModel.ReviewRequest(
                ProtocolSectionModel.REVIEW_INPUT_SCHEMA,
                requireCurrentTask(context, candidate).protocol().studyType(),
                candidate.sectionCode(),
                candidate.content(),
                readList(candidate.usedEvidenceKeysJson()),
                readList(candidate.usedEvidenceKeysJson()));
        var invocation = modelCalls.invokeStructured(
                requireCurrentTask(context, candidate).task(),
                candidate.baseVersionNo(),
                prompt,
                route,
                ProtocolSectionModel.REVIEW_INPUT_SCHEMA,
                ProtocolSectionModel.REVIEW_OUTPUT_SCHEMA,
                write(request),
                () -> route.model().reviewProtocolSection(request, prompt));
        ProtocolSectionModel.ReviewAdvisory advisory =
                validateReview(
                        invocation.output(),
                        Set.copyOf(readList(
                                candidate.usedEvidenceKeysJson())));
        Instant now = clock.instant();
        var review = new ProtocolModelGovernanceRepository.ReviewData(
                UUID.randomUUID(),
                context.actor().hospitalId(),
                context.project().id(),
                candidate.id(),
                invocation.modelCallId(),
                candidate.contentSha256(),
                advisory.severity(),
                write(advisory.issues()),
                advisory.summary().strip(),
                true,
                now);
        persistence.saveReview(candidate, review);
        audit.record(
                context.actor(),
                "WORKSPACE_PROTOCOL_MODEL_REVIEW_COMPLETED",
                "RESEARCH_PROJECT",
                context.project().projectKey());
        return reviewView(context.project().projectKey(), review);
    }

    public DesignAdviceView adviseObservationalDesign(
            WorkspaceReadModelService.WorkspaceContext context) {
        requireEditable(context);
        AgentWorkflowRepository.TaskData task = readModels.latestTask(context);
        if (task == null
                || !"STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN".equals(
                task.currentStep())
                || !"WAITING_CONFIRMATION".equals(task.status())) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_NOT_ALLOWED",
                    "只有等待研究者确认的观察性研究设计可以请求模型辅助建议");
        }
        var recommendation = designRecommendation(task);
        var request = new ObservationalDesignModel.AdviceRequest(
                ObservationalDesignModel.INPUT_SCHEMA,
                recommendation.algorithmVersion(),
                recommendation.recommendedStudyType().name(),
                recommendation.primaryOutcomeCandidate(),
                recommendation.alternatives().stream()
                        .map(value -> new ObservationalDesignModel.RuleAlternative(
                                value.studyType().name(),
                                value.rank(),
                                value.score(),
                                value.feasibilityStatus(),
                                value.missingFields(),
                                value.biasRisks()))
                        .toList(),
                recommendation.unresolvedItems(),
                recommendation.requiredConfirmations());
        var route = models.resolve(LogicalModelType.RESEARCH_REASONING);
        var prompt = prompts.require("OBSERVATIONAL_DESIGN_ADVICE");
        var invocation = modelCalls.invokeStructured(
                task,
                1,
                prompt,
                route,
                ObservationalDesignModel.INPUT_SCHEMA,
                ObservationalDesignModel.OUTPUT_SCHEMA,
                write(request),
                () -> route.model().adviseObservationalDesign(request, prompt));
        var advice = requireSafeAdvice(invocation.output());
        List<String> conflicts = alignmentConflicts(recommendation, advice);
        String status = conflicts.isEmpty() ? "ALIGNED" : "CONFLICT";
        Instant now = clock.instant();
        String adviceJson = write(advice);
        var stored = new ProtocolModelGovernanceRepository.DesignAdviceData(
                UUID.randomUUID(),
                context.actor().hospitalId(),
                context.project().id(),
                task.id(),
                invocation.modelCallId(),
                recommendation.algorithmVersion(),
                prompt.version(),
                recommendation.recommendedStudyType().name(),
                advice.selectedStudyType(),
                adviceJson,
                sha256(adviceJson),
                write(conflicts),
                conflicts.size(),
                status,
                true,
                now);
        persistence.saveDesignAdvice(stored);
        audit.record(
                context.actor(),
                "WORKSPACE_OBSERVATIONAL_DESIGN_MODEL_ADVICE_COMPLETED",
                "RESEARCH_PROJECT",
                context.project().projectKey());
        return designAdviceView(context.project().projectKey(), stored);
    }

    @Transactional
    public int applyCandidate(
            WorkspaceReadModelService.WorkspaceContext context,
            String candidateKey,
            long expectedCandidateVersion) {
        requireEditable(context);
        var candidate = resolveCandidate(context, candidateKey);
        if (!"VALIDATED".equals(candidate.status())
                || candidate.version() != expectedCandidateVersion) {
            throw BusinessException.conflict(
                    "MODEL_CANDIDATE_VERSION_CONFLICT",
                    "模型章节候选状态已变化，请刷新后重试");
        }
        var review = repository.findReviewByCandidate(
                        context.actor().hospitalId(), candidate.id())
                .orElseThrow(() -> BusinessException.conflict(
                        "MODEL_REVIEW_REQUIRED",
                        "必须先完成独立模型辅助复核"));
        if ("BLOCKING".equals(review.severity())) {
            throw BusinessException.conflict(
                    "MODEL_REVIEW_BLOCKING",
                    "模型辅助复核存在阻断问题，不能采纳");
        }
        Source current = requireCurrentTask(context, candidate);
        if (current.section().currentVersionNo() != candidate.baseVersionNo()) {
            throw BusinessException.conflict(
                    "PROTOCOL_SECTION_VERSION_CONFLICT",
                    "方案章节版本已变化，请重新生成候选");
        }
        String sectionKey = WorkspaceOpaqueKey.of(
                "sec_", context.project().projectKey(), candidate.sectionCode());
        int appliedVersion = revisions.applyModelCandidate(
                context,
                sectionKey,
                candidate.baseVersionNo(),
                candidate.content());
        if (!repository.markCandidateApplied(
                context.actor().hospitalId(),
                candidate.id(),
                expectedCandidateVersion,
                context.actor().userId(),
                clock.instant(),
                appliedVersion)) {
            throw BusinessException.conflict(
                    "MODEL_CANDIDATE_VERSION_CONFLICT",
                    "模型章节候选状态已变化，请刷新后重试");
        }
        audit.record(
                context.actor(),
                "WORKSPACE_PROTOCOL_MODEL_CANDIDATE_APPLIED",
                "RESEARCH_PROJECT",
                context.project().projectKey());
        return appliedVersion;
    }

    public List<CandidateView> candidates(
            WorkspaceReadModelService.WorkspaceContext context) {
        return repository.findCandidates(
                        context.actor().hospitalId(), context.project().id()).stream()
                .map(value -> view(context.project().projectKey(), value))
                .toList();
    }

    public List<ReviewView> reviews(
            WorkspaceReadModelService.WorkspaceContext context) {
        return repository.findReviews(
                        context.actor().hospitalId(), context.project().id()).stream()
                .map(value -> reviewView(context.project().projectKey(), value))
                .toList();
    }

    public List<DesignAdviceView> designAdvice(
            WorkspaceReadModelService.WorkspaceContext context) {
        return repository.findDesignAdvice(
                        context.actor().hospitalId(), context.project().id())
                .stream()
                .map(value -> designAdviceView(
                        context.project().projectKey(), value))
                .toList();
    }

    public WorkspaceModels.Envelope<List<CandidateView>> candidateEnvelope(
            String projectKey) {
        var context = readModels.resolve(projectKey);
        var aggregate = readModels.aggregate(context);
        return new WorkspaceModels.Envelope<>(
                candidates(context),
                new WorkspaceModels.ResponseMeta(
                        aggregate.cursor().readModelVersion(),
                        clock.instant(),
                        aggregate.cursor().latestEventId()));
    }

    public WorkspaceModels.Envelope<List<ReviewView>> reviewEnvelope(
            String projectKey) {
        var context = readModels.resolve(projectKey);
        var aggregate = readModels.aggregate(context);
        return new WorkspaceModels.Envelope<>(
                reviews(context),
                new WorkspaceModels.ResponseMeta(
                        aggregate.cursor().readModelVersion(),
                clock.instant(),
                aggregate.cursor().latestEventId()));
    }

    public WorkspaceModels.Envelope<List<DesignAdviceView>>
    designAdviceEnvelope(String projectKey) {
        var context = readModels.resolve(projectKey);
        var aggregate = readModels.aggregate(context);
        return new WorkspaceModels.Envelope<>(
                designAdvice(context),
                new WorkspaceModels.ResponseMeta(
                        aggregate.cursor().readModelVersion(),
                        clock.instant(),
                        aggregate.cursor().latestEventId()));
    }

    private ObservationalDesignRecommendationModels.Recommendation
    designRecommendation(AgentWorkflowRepository.TaskData task) {
        try {
            var output = json.readTree(task.outputJson());
            var node = output == null
                    ? null : output.get("observationalDesignRecommendation");
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("任务缺少观察性研究设计规则结果");
            }
            var value = json.treeToValue(
                    node,
                    ObservationalDesignRecommendationModels.Recommendation.class);
            if (value.recommendedStudyType() == null
                    || value.algorithmVersion() == null
                    || value.alternatives() == null
                    || value.unresolvedItems() == null
                    || value.requiredConfirmations() == null) {
                throw new IllegalStateException("观察性研究设计规则结果不完整");
            }
            return value;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("观察性研究设计规则结果损坏", exception);
        }
    }

    private ObservationalDesignModel.Advice requireSafeAdvice(
            ObservationalDesignModel.Advice value) {
        if (value == null
                || !ObservationalDesignModel.OUTPUT_SCHEMA.equals(
                value.schemaVersion())
                || value.selectedStudyType() == null
                || !Set.of("CROSS_SECTIONAL", "COHORT", "CASE_CONTROL")
                .contains(value.selectedStudyType())
                || value.alignment() == null
                || value.rationale() == null
                || value.biasConsiderations() == null
                || value.missingFields() == null
                || value.suggestedConfirmations() == null
                || value.limitations() == null
                || !value.advisoryOnly()) {
            throw new IllegalArgumentException("观察性研究设计模型建议结构不合法");
        }
        requireSafeAdviceText(value.rationale(), 3000);
        if (value.biasConsiderations().size() > 30
                || value.missingFields().size() > 50
                || value.suggestedConfirmations().size() > 30
                || value.limitations().size() > 30) {
            throw new IllegalArgumentException("观察性研究设计模型建议条目过多");
        }
        value.biasConsiderations().forEach(item ->
                requireSafeAdviceText(item, 1000));
        value.missingFields().forEach(item ->
                requireSafeAdviceText(item, 1000));
        value.suggestedConfirmations().forEach(item ->
                requireSafeAdviceText(item, 1000));
        value.limitations().forEach(item ->
                requireSafeAdviceText(item, 1000));
        return value;
    }

    private void requireSafeAdviceText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || INTERNAL_IDENTIFIER.matcher(value).find()
                || EVIDENCE_IDENTIFIER.matcher(value).find()
                || containsApproval(value)
                || value.chars().anyMatch(character ->
                Character.isISOControl(character)
                        && character != '\n'
                        && character != '\r'
                        && character != '\t')) {
            throw new IllegalArgumentException("观察性研究设计模型建议包含不安全内容");
        }
    }

    private List<String> alignmentConflicts(
            ObservationalDesignRecommendationModels.Recommendation rule,
            ObservationalDesignModel.Advice advice) {
        List<String> conflicts = new ArrayList<>();
        if (!rule.recommendedStudyType().name().equals(
                advice.selectedStudyType())) {
            conflicts.add("MODEL_STUDY_TYPE_DIFFERS_FROM_RULE");
        }
        if (!"ALIGNED".equals(advice.alignment())) {
            conflicts.add("MODEL_DID_NOT_DECLARE_RULE_ALIGNMENT");
        }
        LinkedHashSet<String> missing = new LinkedHashSet<>(
                advice.missingFields());
        if (!missing.containsAll(rule.unresolvedItems())) {
            conflicts.add("MODEL_OMITTED_RULE_UNRESOLVED_ITEMS");
        }
        LinkedHashSet<String> confirmations = new LinkedHashSet<>(
                advice.suggestedConfirmations());
        if (!confirmations.containsAll(rule.requiredConfirmations())) {
            conflicts.add("MODEL_OMITTED_REQUIRED_CONFIRMATIONS");
        }
        if (!advice.limitations().contains(
                "仅供科研设计讨论，未经伦理和科研管理审批")) {
            conflicts.add("MANDATORY_DISCLAIMER_MISSING");
        }
        return List.copyOf(conflicts);
    }

    private Source source(
            WorkspaceReadModelService.WorkspaceContext context,
            String sectionKey) {
        AgentWorkflowRepository.TaskData task = requireRevisionTask(context);
        ResearchProtocolRepository.ProtocolData protocol =
                protocols.findByAgentTask(context.actor().hospitalId(), task.id())
                        .orElseThrow(() -> new IllegalStateException(
                                "当前任务缺少研究方案"));
        UUID sectionId = UUID.fromString(artifacts.resolveSectionId(
                context.project().projectKey(), task, sectionKey));
        ResearchProtocolRepository.SectionData section = protocols.findSections(
                        context.actor().hospitalId(), protocol.id()).stream()
                .filter(value -> value.id().equals(sectionId))
                .findFirst()
                .orElseThrow(() -> BusinessException.conflict(
                        "PROJECT_ACTION_NOT_ALLOWED",
                        "方案章节已变化，请刷新后重试"));
        if ("LOCKED".equals(section.status())) {
            throw BusinessException.conflict(
                    "PROTOCOL_SECTION_LOCKED", "锁定章节不能生成模型候选");
        }
        ResearchProtocolRepository.SectionVersionData version =
                protocols.findSectionVersions(
                                context.actor().hospitalId(), section.id()).stream()
                        .filter(value ->
                                value.versionNo() == section.currentVersionNo())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "当前方案章节版本缺失"));
        return new Source(task, protocol, section, version);
    }

    private Source requireCurrentTask(
            WorkspaceReadModelService.WorkspaceContext context,
            ProtocolModelGovernanceRepository.CandidateData candidate) {
        AgentWorkflowRepository.TaskData task = requireRevisionTask(context);
        if (!task.id().equals(candidate.agentTaskId())) {
            throw BusinessException.conflict(
                    "MODEL_CANDIDATE_SUPERSEDED",
                    "模型章节候选已不属于当前方案轮次");
        }
        String sectionKey = WorkspaceOpaqueKey.of(
                "sec_", context.project().projectKey(), candidate.sectionCode());
        return source(context, sectionKey);
    }

    private AgentWorkflowRepository.TaskData requireRevisionTask(
            WorkspaceReadModelService.WorkspaceContext context) {
        AgentWorkflowRepository.TaskData task = readModels.latestTask(context);
        if (task == null
                || !"STEP_17_WAIT_EXPERT_REVIEW".equals(task.currentStep())
                || !"REVISION_REQUIRED".equals(task.status())) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_NOT_ALLOWED",
                    "只有审核退回后的当前方案可以使用模型章节候选");
        }
        return task;
    }

    private ProtocolModelGovernanceRepository.CandidateData resolveCandidate(
            WorkspaceReadModelService.WorkspaceContext context,
            String candidateKey) {
        if (candidateKey == null || candidateKey.isBlank()) {
            throw new IllegalArgumentException("candidateKey 不能为空");
        }
        return repository.findCandidates(
                        context.actor().hospitalId(), context.project().id()).stream()
                .filter(value -> WorkspaceOpaqueKey.of(
                        "cand_", context.project().projectKey(),
                        value.id().toString()).equals(candidateKey))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound(
                        "模型章节候选不存在"));
    }

    private ProtocolSectionModel.ReviewAdvisory validateReview(
            ProtocolSectionModel.ReviewAdvisory value,
            Set<String> allowedEvidenceIdentifiers) {
        if (value == null
                || !ProtocolSectionModel.REVIEW_OUTPUT_SCHEMA.equals(
                value.schemaVersion())
                || !value.advisoryOnly()
                || !REVIEW_SEVERITIES.contains(value.severity())
                || value.summary() == null
                || value.summary().isBlank()
                || value.summary().length() > 2000
                || INTERNAL_IDENTIFIER.matcher(value.summary()).find()
                || containsUnallowedEvidence(
                value.summary(), allowedEvidenceIdentifiers)
                || containsApproval(value.summary())
                || value.issues() == null
                || value.issues().size() > 50) {
            throw new IllegalArgumentException("模型辅助复核输出不合法");
        }
        for (var issue : value.issues()) {
            if (issue == null
                    || !ISSUE_SEVERITIES.contains(issue.severity())
                    || invalidReviewText(issue.type(), 80)
                    || invalidReviewText(issue.location(), 500)
                    || invalidReviewText(issue.message(), 2000)
                    || invalidReviewText(issue.suggestedChange(), 2000)
                    || containsUnallowedEvidence(
                    issue.location(), allowedEvidenceIdentifiers)
                    || containsUnallowedEvidence(
                    issue.message(), allowedEvidenceIdentifiers)
                    || containsUnallowedEvidence(
                    issue.suggestedChange(), allowedEvidenceIdentifiers)
                    || containsApproval(issue.message())
                    || containsApproval(issue.suggestedChange())) {
                throw new IllegalArgumentException("模型辅助复核问题不合法");
            }
        }
        return value;
    }

    private boolean invalidReviewText(String value, int limit) {
        return value == null || value.isBlank() || value.length() > limit
                || INTERNAL_IDENTIFIER.matcher(value).find();
    }

    private boolean containsApproval(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.contains("APPROVED")
                || value.contains("已批准")
                || value.contains("审核通过")
                || value.contains("伦理通过");
    }

    private boolean containsUnallowedEvidence(
            String value, Set<String> allowed) {
        var matcher = EVIDENCE_IDENTIFIER.matcher(value);
        while (matcher.find()) {
            String normalized = normalizeEvidenceIdentifier(
                    matcher.group());
            boolean found = allowed.stream()
                    .map(this::normalizeEvidenceIdentifier)
                    .anyMatch(normalized::equals);
            if (!found) return true;
        }
        return false;
    }

    private String normalizeEvidenceIdentifier(String value) {
        return value.replaceAll("\\s+", "")
                .replace('：', ':')
                .toUpperCase(Locale.ROOT);
    }

    private void requireEditable(
            WorkspaceReadModelService.WorkspaceContext context) {
        if (!context.canEdit()) {
            throw BusinessException.forbidden(
                    "当前账号没有使用模型辅助功能的权限");
        }
    }

    private List<String> readList(String value) {
        try {
            if (value == null || value.isBlank()) return List.of();
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("模型治理证据列表损坏", exception);
        }
    }

    private CandidateView view(
            String projectKey,
            ProtocolModelGovernanceRepository.CandidateData value) {
        return new CandidateView(
                WorkspaceOpaqueKey.of("cand_", projectKey, value.id().toString()),
                WorkspaceOpaqueKey.of("sec_", projectKey, value.sectionCode()),
                value.sectionCode(),
                value.baseVersionNo(),
                value.status(),
                value.content(),
                readList(value.usedEvidenceKeysJson()),
                readList(value.issuesToConfirmJson()),
                value.generatedAt(),
                value.version(),
                value.appliedVersionNo());
    }

    private ReviewView reviewView(
            String projectKey,
            ProtocolModelGovernanceRepository.ReviewData value) {
        return new ReviewView(
                WorkspaceOpaqueKey.of(
                        "mrev_", projectKey, value.id().toString()),
                WorkspaceOpaqueKey.of(
                        "cand_", projectKey, value.candidateId().toString()),
                value.severity(),
                readReviewIssues(value.issuesJson()),
                value.summary(),
                true,
                value.createdAt());
    }

    private DesignAdviceView designAdviceView(
            String projectKey,
            ProtocolModelGovernanceRepository.DesignAdviceData value) {
        try {
            return new DesignAdviceView(
                    WorkspaceOpaqueKey.of(
                            "dadv_", projectKey, value.id().toString()),
                    value.ruleVersion(),
                    value.ruleRecommendedStudyType(),
                    value.modelSelectedStudyType(),
                    json.readValue(
                            value.adviceJson(),
                            ObservationalDesignModel.Advice.class),
                    readList(value.conflictsJson()),
                    value.status(),
                    true,
                    value.createdAt());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "观察性研究设计模型建议记录损坏", exception);
        }
    }

    private List<ProtocolSectionModel.ReviewIssue> readReviewIssues(String value) {
        try {
            return json.readValue(
                    value,
                    new TypeReference<List<ProtocolSectionModel.ReviewIssue>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("模型辅助复核记录损坏", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("模型治理序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("模型治理哈希失败", exception);
        }
    }

    private record Source(
            AgentWorkflowRepository.TaskData task,
            ResearchProtocolRepository.ProtocolData protocol,
            ResearchProtocolRepository.SectionData section,
            ResearchProtocolRepository.SectionVersionData version) {}

    public record CandidateView(
            String candidateKey,
            String sectionKey,
            String sectionCode,
            int baseVersionNo,
            String status,
            String content,
            List<String> usedEvidenceIdentifiers,
            List<String> issuesToConfirm,
            Instant generatedAt,
            long version,
            Integer appliedVersionNo) {}

    public record ReviewView(
            String reviewKey,
            String candidateKey,
            String severity,
            List<ProtocolSectionModel.ReviewIssue> issues,
            String summary,
            boolean advisoryOnly,
            Instant createdAt) {}

    public record DesignAdviceView(
            String adviceKey,
            String ruleVersion,
            String ruleRecommendedStudyType,
            String modelSelectedStudyType,
            ObservationalDesignModel.Advice advice,
            List<String> conflicts,
            String status,
            boolean advisoryOnly,
            Instant createdAt) {}
}
