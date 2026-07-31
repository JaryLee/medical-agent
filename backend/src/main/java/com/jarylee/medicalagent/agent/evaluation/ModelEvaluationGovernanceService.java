package com.jarylee.medicalagent.agent.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.safety.SensitiveContentPolicy;
import com.jarylee.medicalagent.workspace.WorkspaceModels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ModelEvaluationGovernanceService {
    private static final Set<String> RESPONSIBILITIES =
            Set.of("MEDICAL_REVIEW", "STATISTICAL_REVIEW");
    private static final Set<String> RECOMMENDATIONS =
            Set.of("ACCEPT", "REVISE", "REJECT");
    private static final String DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批";
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("^[^\\p{Cntrl}]{16,128}$");
    private static final String START_REQUEST =
            "START_ANONYMOUS_MODEL_EVALUATION:v1";

    private final ModelEvaluationRepository repository;
    private final AnonymousResearchCaseRegistry cases;
    private final ResearchModelEvaluationService evaluator;
    private final ModelRouter models;
    private final PromptTemplateRegistry prompts;
    private final CurrentUserProvider currentUser;
    private final SensitiveContentPolicy sensitiveContent;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public ModelEvaluationGovernanceService(
            ModelEvaluationRepository repository,
            AnonymousResearchCaseRegistry cases,
            ResearchModelEvaluationService evaluator,
            ModelRouter models,
            PromptTemplateRegistry prompts,
            CurrentUserProvider currentUser,
            SensitiveContentPolicy sensitiveContent,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.cases = cases;
        this.evaluator = evaluator;
        this.models = models;
        this.prompts = prompts;
        this.currentUser = currentUser;
        this.sensitiveContent = sensitiveContent;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public WorkspaceModels.Envelope<EvaluationView> start(
            String idempotencyKey) {
        AuthenticatedUser actor = currentUser.requireUser();
        requireRole(actor, Role.HOSPITAL_ADMIN);
        validateIdempotencyKey(idempotencyKey);
        String requestSha256 = sha256(START_REQUEST);
        var replay = repository.findRunByStartIdempotency(
                actor.hospitalId(), actor.userId(), idempotencyKey);
        if (replay.isPresent()) {
            requireSameRequest(replay.get().requestSha256(), requestSha256);
            return envelope(replay.get(), actor.hospitalId());
        }
        var caseSet = cases.current();
        var route = models.resolve(LogicalModelType.RESEARCH_FAST);
        if (!"mock".equalsIgnoreCase(route.model().provider())) {
            throw BusinessException.conflict(
                    "EXTERNAL_MODEL_EVALUATION_NOT_ENABLED",
                    "当前评测入口只允许匿名合成案例的离线测试模型；真实模型评测尚未启用独立预算与调用审计");
        }
        var prompt = prompts.require("STEP_01_PARSE_IDEA");
        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        try {
            repository.createRun(new ModelEvaluationRepository.RunData(
                    runId,
                    actor.hospitalId(),
                    actor.userId(),
                    caseSet.schemaVersion(),
                    caseSet.dataClassification(),
                    prompt.version(),
                    route.policyVersion(),
                    idempotencyKey,
                    requestSha256,
                    "RUNNING",
                    caseSet.cases().size(),
                    null,
                    null,
                    null,
                    startedAt,
                    null,
                    0));
        } catch (RuntimeException exception) {
            var racedReplay = repository.findRunByStartIdempotency(
                    actor.hospitalId(), actor.userId(), idempotencyKey);
            if (racedReplay.isPresent()) {
                requireSameRequest(
                        racedReplay.get().requestSha256(), requestSha256);
                return envelope(racedReplay.get(), actor.hospitalId());
            }
            throw exception;
        }
        var result = evaluator.evaluate(
                caseSet, caseSet.cases(), route.model(), prompt);
        Instant completedAt = clock.instant();
        for (var item : result.cases()) {
            String metrics = write(item);
            repository.saveCaseResult(
                    new ModelEvaluationRepository.CaseData(
                            UUID.randomUUID(),
                            actor.hospitalId(),
                            runId,
                            item.caseId(),
                            LogicalModelType.RESEARCH_FAST.name(),
                            route.model().provider(),
                            route.model().modelName(),
                            sha256(metrics),
                            item.passed(),
                            metrics,
                            item.passed() ? null
                                    : String.join(",", item.violations()),
                            completedAt));
        }
        String report = write(Map.of(
                "schemaVersion", "model-evaluation-report/v1",
                "dataClassification", caseSet.dataClassification(),
                "automatedResult", result,
                "expertScoring", "REQUIRED_TWO_DISTINCT_EXPERTS",
                "disclaimer", DISCLAIMER));
        repository.completeAutomation(
                actor.hospitalId(),
                runId,
                result.passedCount(),
                sha256(report),
                report,
                completedAt);
        audit.record(
                actor,
                "MODEL_EVALUATION_AUTOMATION_COMPLETED",
                "MODEL_EVALUATION",
                evaluationKey(actor.hospitalId(), runId));
        return envelope(
                repository.findRun(actor.hospitalId(), runId).orElseThrow(),
                actor.hospitalId());
    }

    public WorkspaceModels.Envelope<List<EvaluationView>> list() {
        AuthenticatedUser actor = requireEvaluationViewer();
        var runs = repository.findRuns(actor.hospitalId());
        var views = runs.stream()
                .map(value -> view(value, actor.hospitalId()))
                .toList();
        long version = runs.stream()
                .mapToLong(value -> value.version() + 1)
                .sum();
        return new WorkspaceModels.Envelope<>(
                views,
                new WorkspaceModels.ResponseMeta(
                        version, clock.instant(), 0));
    }

    public WorkspaceModels.Envelope<EvaluationView> get(
            String evaluationKey) {
        AuthenticatedUser actor = requireEvaluationViewer();
        return envelope(resolve(actor.hospitalId(), evaluationKey),
                actor.hospitalId());
    }

    @Transactional
    public WorkspaceModels.Envelope<EvaluationView> submitScore(
            String evaluationKey, String idempotencyKey,
            ScoreRequest request) {
        AuthenticatedUser actor = currentUser.requireUser();
        requireRole(actor, Role.EXPERT);
        validateIdempotencyKey(idempotencyKey);
        ScoreRequest normalized = validate(request);
        String requestSha256 = sha256(
                evaluationKey + ":" + write(normalized));
        var replay = repository.findExpertScoreByIdempotency(
                actor.hospitalId(), actor.userId(), idempotencyKey);
        if (replay.isPresent()) {
            requireSameRequest(
                    replay.get().requestSha256(), requestSha256);
            return envelope(repository.findRun(
                            actor.hospitalId(),
                            replay.get().evaluationRunId())
                    .orElseThrow(), actor.hospitalId());
        }
        var run = resolve(actor.hospitalId(), evaluationKey);
        if (!"WAITING_EXPERT_SCORING".equals(run.status())) {
            throw BusinessException.conflict(
                    "MODEL_EVALUATION_NOT_SCORABLE",
                    "当前模型评测批次不可评分");
        }
        List<ModelEvaluationRepository.ExpertScoreData> existing =
                repository.findExpertScores(actor.hospitalId(), run.id());
        if (existing.stream().anyMatch(value ->
                value.responsibility().equals(normalized.responsibility())
                        || value.reviewerId().equals(actor.userId()))) {
            throw BusinessException.conflict(
                    "MODEL_EVALUATION_INDEPENDENCE_REQUIRED",
                    "医学与统计评分必须由两名不同专家各提交一次");
        }
        try {
            repository.saveExpertScore(
                    new ModelEvaluationRepository.ExpertScoreData(
                            UUID.randomUUID(),
                            actor.hospitalId(),
                            run.id(),
                            normalized.responsibility(),
                            actor.userId(),
                            (short) normalized.correctnessScore(),
                            (short) normalized.completenessScore(),
                            (short) normalized.safetyScore(),
                            (short) normalized.actionabilityScore(),
                            normalized.recommendation(),
                            normalized.comment().strip(),
                            idempotencyKey,
                            requestSha256,
                            clock.instant()));
        } catch (IllegalStateException exception) {
            var racedReplay = findScoreReplaySafely(
                    actor.hospitalId(), actor.userId(), idempotencyKey);
            if (racedReplay.isPresent()) {
                requireSameRequest(
                        racedReplay.get().requestSha256(), requestSha256);
                return envelope(repository.findRun(
                                actor.hospitalId(),
                                racedReplay.get().evaluationRunId())
                        .orElseThrow(), actor.hospitalId());
            }
            throw BusinessException.conflict(
                    "MODEL_EVALUATION_INDEPENDENCE_REQUIRED",
                    "医学与统计评分必须由两名不同专家各提交一次");
        }
        List<ModelEvaluationRepository.ExpertScoreData> after =
                repository.findExpertScores(actor.hospitalId(), run.id());
        if (after.size() == 2) {
            repository.markCompleted(actor.hospitalId(), run.id());
        }
        audit.record(
                actor,
                "MODEL_EVALUATION_EXPERT_SCORE_SUBMITTED",
                "MODEL_EVALUATION",
                evaluationKey);
        return envelope(
                repository.findRun(actor.hospitalId(), run.id()).orElseThrow(),
                actor.hospitalId());
    }

    private WorkspaceModels.Envelope<EvaluationView> envelope(
            ModelEvaluationRepository.RunData run, UUID hospitalId) {
        return new WorkspaceModels.Envelope<>(
                view(run, hospitalId),
                new WorkspaceModels.ResponseMeta(
                        run.version() + 1, clock.instant(), 0));
    }

    private void validateIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key 必须是 16 到 128 个非控制字符");
        }
    }

    private void requireSameRequest(String existing, String requested) {
        if (!requested.equals(existing)) {
            throw BusinessException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key 已用于不同的模型评测请求");
        }
    }

    private java.util.Optional<
            ModelEvaluationRepository.ExpertScoreData>
    findScoreReplaySafely(
            UUID hospitalId, UUID reviewerId, String idempotencyKey) {
        try {
            return repository.findExpertScoreByIdempotency(
                    hospitalId, reviewerId, idempotencyKey);
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private AuthenticatedUser requireEvaluationViewer() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (!actor.hasRole(Role.HOSPITAL_ADMIN)
                && !actor.hasRole(Role.AUDIT_ADMIN)
                && !actor.hasRole(Role.EXPERT)) {
            throw BusinessException.forbidden("当前账号不能查看模型评测");
        }
        return actor;
    }

    private void requireRole(AuthenticatedUser actor, Role role) {
        if (!actor.hasRole(role)) {
            throw BusinessException.forbidden("当前账号不能执行模型评测动作");
        }
    }

    private ScoreRequest validate(ScoreRequest value) {
        if (value == null
                || !RESPONSIBILITIES.contains(value.responsibility())
                || !RECOMMENDATIONS.contains(value.recommendation())
                || !validScore(value.correctnessScore())
                || !validScore(value.completenessScore())
                || !validScore(value.safetyScore())
                || !validScore(value.actionabilityScore())
                || value.comment() == null
                || value.comment().isBlank()
                || value.comment().length() > 2000
                || !sensitiveContent.assess(value.comment())
                .canSendToExternalModel()) {
            throw new IllegalArgumentException(
                    "专家评分字段不完整、分值超限或包含直接身份标识");
        }
        return value;
    }

    private boolean validScore(int value) {
        return value >= 1 && value <= 5;
    }

    private ModelEvaluationRepository.RunData resolve(
            UUID hospitalId, String evaluationKey) {
        if (evaluationKey == null || evaluationKey.isBlank()) {
            throw BusinessException.notFound("模型评测批次不存在");
        }
        return repository.findRuns(hospitalId).stream()
                .filter(value -> evaluationKey(hospitalId, value.id())
                        .equals(evaluationKey))
                .findFirst()
                .orElseThrow(() ->
                        BusinessException.notFound("模型评测批次不存在"));
    }

    private EvaluationView view(
            ModelEvaluationRepository.RunData run, UUID hospitalId) {
        List<CaseView> caseViews = repository.findCaseResults(
                        hospitalId, run.id()).stream()
                .map(value -> new CaseView(
                        value.caseKey(),
                        value.logicalModelType(),
                        value.provider(),
                        value.modelName(),
                        value.passed(),
                        readMap(value.metricsJson()),
                        value.errorCode(),
                        value.evaluatedAt()))
                .toList();
        List<ExpertScoreView> scoreViews = repository.findExpertScores(
                        hospitalId, run.id()).stream()
                .map(value -> new ExpertScoreView(
                        value.responsibility(),
                        responsibilityLabel(value.responsibility()),
                        value.correctnessScore(),
                        value.completenessScore(),
                        value.safetyScore(),
                        value.actionabilityScore(),
                        value.recommendation(),
                        value.comment(),
                        value.submittedAt()))
                .toList();
        return new EvaluationView(
                evaluationKey(hospitalId, run.id()),
                run.datasetVersion(),
                run.dataClassification(),
                run.promptVersion(),
                run.routePolicyVersion(),
                run.status(),
                statusLabel(run.status()),
                run.caseCount(),
                run.passedCount(),
                caseViews,
                scoreViews,
                scoreViews.size() < 2,
                DISCLAIMER,
                run.startedAt(),
                run.completedAt());
    }

    private String responsibilityLabel(String value) {
        return "MEDICAL_REVIEW".equals(value) ? "医学专家评分" : "统计专家评分";
    }

    private String statusLabel(String value) {
        return switch (value) {
            case "RUNNING" -> "自动评测进行中";
            case "WAITING_EXPERT_SCORING" -> "等待两名独立专家评分";
            case "COMPLETED" -> "自动评测与双专家评分已完成";
            case "FAILED" -> "自动评测失败";
            default -> "未知状态";
        };
    }

    private String evaluationKey(UUID hospitalId, UUID runId) {
        return "eval_" + sha256(hospitalId + ":" + runId)
                .substring(0, 26).toUpperCase();
    }

    private Map<String, Object> readMap(String value) {
        try {
            return json.readValue(
                    value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("模型评测指标记录损坏", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("模型评测序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("模型评测哈希失败", exception);
        }
    }

    public record ScoreRequest(
            String responsibility,
            int correctnessScore,
            int completenessScore,
            int safetyScore,
            int actionabilityScore,
            String recommendation,
            String comment) {}

    public record EvaluationView(
            String evaluationKey,
            String datasetVersion,
            String dataClassification,
            String promptVersion,
            String routePolicyVersion,
            String status,
            String statusLabel,
            int caseCount,
            Integer passedCount,
            List<CaseView> cases,
            List<ExpertScoreView> expertScores,
            boolean expertScoringRequired,
            String disclaimer,
            Instant startedAt,
            Instant automatedCompletedAt) {}

    public record CaseView(
            String caseKey,
            String logicalModelType,
            String provider,
            String modelName,
            boolean passed,
            Map<String, Object> metrics,
            String errorCode,
            Instant evaluatedAt) {}

    public record ExpertScoreView(
            String responsibility,
            String responsibilityLabel,
            int correctnessScore,
            int completenessScore,
            int safetyScore,
            int actionabilityScore,
            String recommendation,
            String comment,
            Instant submittedAt) {}
}
