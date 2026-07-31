package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.document.DocumentExportService;
import com.jarylee.medicalagent.review.ExpertReviewModels.CommentType;
import com.jarylee.medicalagent.review.ExpertReviewModels.Decision;
import com.jarylee.medicalagent.review.ExpertReviewModels.Responsibility;
import com.jarylee.medicalagent.review.ExpertReviewService;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.AgentWorkflowService;
import com.jarylee.medicalagent.workspace.WorkspaceModels.Envelope;
import com.jarylee.medicalagent.workspace.WorkspaceModels.WorkspaceSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class WorkspaceActionService {
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("^[^\\p{Cntrl}]{16,128}$");
    private static final List<String> SUPPORTED_ACTIONS = List.of(
            "START_RESEARCH_IDEA",
            "SUBMIT_CLARIFICATIONS",
            "CONFIRM_RESEARCH_DIRECTION",
            "CONFIRM_SEARCH_STRATEGY",
            "CONFIRM_OBSERVATIONAL_DESIGN",
            "UPDATE_PROTOCOL_SECTION",
            "REGENERATE_PROTOCOL_SECTION",
            "SUBMIT_PROTOCOL_REVISION",
            "ADD_INTERNAL_REVIEW_COMMENT",
            "SUBMIT_MEDICAL_REVIEW",
            "SUBMIT_STATISTICAL_REVIEW",
            "CONFIRM_INTERNAL_REVIEW",
            "EXPORT_RESEARCH_DRAFT",
            "CANCEL_RESEARCH_WORKFLOW",
            "RETRY_RESEARCH_WORKFLOW");

    private final WorkspaceReadModelService readModels;
    private final WorkspaceRepository workspace;
    private final AgentWorkflowService workflows;
    private final WorkspaceArtifactReadService artifacts;
    private final WorkspaceProtocolRevisionService protocolRevisions;
    private final ExpertReviewService reviews;
    private final DocumentExportService exports;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkspaceActionService(
            WorkspaceReadModelService readModels,
            WorkspaceRepository workspace,
            AgentWorkflowService workflows,
            WorkspaceArtifactReadService artifacts,
            WorkspaceProtocolRevisionService protocolRevisions,
            ExpertReviewService reviews,
            DocumentExportService exports,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.readModels = readModels;
        this.workspace = workspace;
        this.workflows = workflows;
        this.artifacts = artifacts;
        this.protocolRevisions = protocolRevisions;
        this.reviews = reviews;
        this.exports = exports;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Envelope<WorkspaceSummary> execute(
            String projectKey,
            String actionCode,
            String idempotencyKey,
            long expectedReadModelVersion,
            JsonNode body) {
        validateHeaders(actionCode, idempotencyKey, expectedReadModelVersion);
        var context = readModels.resolve(projectKey);
        boolean expertReviewAction = List.of(
                "ADD_INTERNAL_REVIEW_COMMENT",
                "SUBMIT_MEDICAL_REVIEW",
                "SUBMIT_STATISTICAL_REVIEW").contains(actionCode)
                && context.actor().hasRole(
                com.jarylee.medicalagent.auth.Role.EXPERT);
        if (!context.canEdit() && !expertReviewAction) {
            throw BusinessException.forbidden(
                    "当前账号只有查看权限，不能执行课题动作");
        }
        JsonNode effectiveBody = body == null
                ? json.createObjectNode() : body;
        String requestHash = WorkspaceRequestHash.sha256(
                json, actionCode, expectedReadModelVersion, effectiveBody);

        var existing = workspace.findCommand(
                context.actor().hospitalId(), context.project().id(),
                context.actor().userId(), idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        var aggregate = readModels.aggregate(context);
        if (aggregate.cursor().readModelVersion() != expectedReadModelVersion) {
            throw versionConflict();
        }
        boolean allowed = aggregate.summary().allowedActions().stream()
                .anyMatch(action -> action.enabled()
                        && action.code().equals(actionCode));
        if (!allowed) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_NOT_ALLOWED",
                    "当前课题状态或账号权限不允许执行该动作");
        }

        Instant now = clock.instant();
        UUID commandId = UUID.randomUUID();
        var reservation = workspace.reserve(
                new WorkspaceRepository.CommandDraft(
                        commandId,
                        context.actor().hospitalId(),
                        context.project().id(),
                        context.actor().userId(),
                        actionCode,
                        idempotencyKey,
                        requestHash,
                        expectedReadModelVersion,
                        now));
        if (reservation.versionConflict()) throw versionConflict();
        if (!reservation.acquired()) {
            return replay(reservation.existing(), requestHash);
        }

        try {
            executeDomainAction(
                    context, actionCode, idempotencyKey, effectiveBody);
            var resultAggregate = readModels.aggregate(context);
            Envelope<WorkspaceSummary> response = new Envelope<>(
                    resultAggregate.summary(),
                    new WorkspaceModels.ResponseMeta(
                            resultAggregate.cursor().readModelVersion(),
                            clock.instant(),
                            resultAggregate.cursor().latestEventId()));
            String responseJson = write(response);
            workspace.completeCommand(
                    context.actor().hospitalId(),
                    commandId,
                    resultAggregate.cursor().readModelVersion(),
                    responseJson,
                    clock.instant());
            audit.record(
                    context.actor(),
                    "WORKSPACE_ACTION_" + actionCode,
                    "RESEARCH_PROJECT",
                    context.project().projectKey());
            return response;
        } catch (RuntimeException exception) {
            workspace.abortCommand(context.actor().hospitalId(), commandId);
            throw exception;
        }
    }

    private void executeDomainAction(
            WorkspaceReadModelService.WorkspaceContext context,
            String actionCode,
            String idempotencyKey,
            JsonNode body) {
        switch (actionCode) {
            case "START_RESEARCH_IDEA" -> workflows.create(
                    context.project().id(),
                    requiredText(body, "idea", 2000),
                    taskIdempotencyKey(idempotencyKey));
            case "SUBMIT_CLARIFICATIONS" -> {
                AgentWorkflowRepository.TaskData task = requireTask(context);
                workflows.submitClarifications(
                        task.id(), requiredAnswers(body.path("answers")));
            }
            case "CONFIRM_RESEARCH_DIRECTION" -> {
                String directionKey = requiredText(body, "directionKey", 64);
                var selection = readModels.resolveDirection(
                        context, directionKey);
                workflows.confirm(
                        selection.task().id(),
                        selection.rawDirectionId(),
                        selection.candidateSetId(),
                        selection.candidateSetHash());
            }
            case "CONFIRM_SEARCH_STRATEGY" ->
                    workflows.confirmSearchStrategy(
                            requireTask(context).id(),
                            requiredText(body, "pubmedQuery", 4000));
            case "CONFIRM_OBSERVATIONAL_DESIGN" ->
                    workflows.confirmObservationalDesign(
                            requireTask(context).id(),
                            studyType(body.path("studyType").asText()),
                            requiredText(body, "primaryOutcome", 1000),
                            requiredTrue(
                                    body,
                                    "authorizeProtocolGeneration",
                                    "必须明确授权生成科研方案草案"));
            case "UPDATE_PROTOCOL_SECTION" ->
                    protocolRevisions.updateSection(
                            context,
                            requiredText(body, "sectionKey", 64),
                            requiredPositiveLong(
                                    body,
                                    "expectedSectionVersion").intValue(),
                            requiredText(body, "content", 30000),
                            optionalText(body, "changeReason", 80));
            case "REGENERATE_PROTOCOL_SECTION" ->
                    protocolRevisions.regenerateSection(
                            context,
                            requiredText(body, "sectionKey", 64),
                            requiredPositiveLong(
                                    body,
                                    "expectedSectionVersion").intValue(),
                            optionalText(body, "changeReason", 80));
            case "SUBMIT_PROTOCOL_REVISION" ->
                    protocolRevisions.submitRevision(
                            context, idempotencyKey);
            case "ADD_INTERNAL_REVIEW_COMMENT" -> {
                AgentWorkflowRepository.TaskData task =
                        requireTask(context);
                String targetType = requiredText(
                        body, "targetType", 40);
                UUID sectionId = null;
                Integer sectionVersion = null;
                UUID checkItemId = null;
                if ("PROTOCOL_SECTION".equals(targetType)) {
                    sectionId = UUID.fromString(
                            artifacts.resolveSectionId(
                                    context.project().projectKey(),
                                    task,
                                    requiredText(
                                            body, "targetKey", 64)));
                    sectionVersion = requiredPositiveLong(
                            body, "targetVersion").intValue();
                } else if ("STROBE_ITEM".equals(targetType)) {
                    checkItemId = UUID.fromString(
                            artifacts.resolveCheckItemId(
                                    context.project().projectKey(),
                                    task,
                                    requiredText(
                                            body, "targetKey", 64)));
                } else {
                    throw new IllegalArgumentException(
                            "targetType 只支持方案章节或 STROBE 检查项");
                }
                reviews.addComment(
                        task.id(),
                        sectionId,
                        sectionVersion,
                        checkItemId,
                        commentType(requiredText(
                                body, "commentType", 40)),
                        responsibility(requiredText(
                                body, "responsibility", 40)),
                        requiredText(body, "content", 2000));
            }
            case "SUBMIT_MEDICAL_REVIEW" ->
                    submitReview(
                            context, body,
                            Responsibility.MEDICAL_REVIEW);
            case "SUBMIT_STATISTICAL_REVIEW" ->
                    submitReview(
                            context, body,
                            Responsibility.STATISTICAL_REVIEW);
            case "CONFIRM_INTERNAL_REVIEW" ->
                    reviews.ownerConfirm(
                            requireTask(context).id(),
                            requiredPositiveLong(
                                    body, "reviewVersion"));
            case "EXPORT_RESEARCH_DRAFT" -> {
                AgentWorkflowRepository.TaskData task =
                        requireTask(context);
                UUID templateId = UUID.fromString(
                        artifacts.resolveTemplateId(
                                context.project().projectKey(),
                                requiredText(
                                        body, "templateKey", 64)));
                UUID styleId = UUID.fromString(
                        artifacts.resolveStyleId(
                                context.project().projectKey(),
                                requiredText(
                                        body, "styleKey", 64)));
                exports.confirmAndExport(
                        task.id(),
                        templateId,
                        styleId,
                        requiredTrue(
                                body,
                                "confirmReviewedContent",
                                "必须确认导出当前已审核锁定内容"));
            }
            case "CANCEL_RESEARCH_WORKFLOW" ->
                    workflows.cancel(requireTask(context).id());
            case "RETRY_RESEARCH_WORKFLOW" ->
                    workflows.retry(requireTask(context).id());
            default -> throw new IllegalArgumentException("不支持的课题动作");
        }
    }

    private AgentWorkflowRepository.TaskData requireTask(
            WorkspaceReadModelService.WorkspaceContext context) {
        AgentWorkflowRepository.TaskData task = readModels.latestTask(context);
        if (task == null) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_NOT_ALLOWED", "当前课题没有可操作的流程");
        }
        return task;
    }

    private Envelope<WorkspaceSummary> replay(
            WorkspaceRepository.CommandData command,
            String requestHash) {
        if (!command.requestSha256().equals(requestHash)) {
            throw BusinessException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "同一幂等键不能用于不同的动作请求");
        }
        if (!"COMPLETED".equals(command.status())
                || command.responseJson() == null) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_IN_PROGRESS",
                    "相同动作正在处理，请稍后使用同一幂等键重试");
        }
        try {
            return json.readValue(
                    command.responseJson(),
                    new TypeReference<Envelope<WorkspaceSummary>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("V2 动作幂等响应损坏", exception);
        }
    }

    private void validateHeaders(
            String actionCode,
            String idempotencyKey,
            long expectedReadModelVersion) {
        if (!SUPPORTED_ACTIONS.contains(actionCode)) {
            throw new IllegalArgumentException("不支持的课题动作");
        }
        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key 必须是 16 到 128 个非控制字符");
        }
        if (expectedReadModelVersion < 1) {
            throw new IllegalArgumentException("If-Match 读模型版本必须大于 0");
        }
    }

    private String requiredText(JsonNode body, String field, int maxLength) {
        String value = body.path(field).asText("").strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " 不能超过 " + maxLength + " 字");
        }
        return value;
    }

    private String optionalText(
            JsonNode body, String field, int maxLength) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isTextual()) {
            throw new IllegalArgumentException(
                    field + " 必须是文本");
        }
        String value = node.asText().strip();
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " 不能超过 " + maxLength + " 字");
        }
        return value.isBlank() ? null : value;
    }

    private Map<String, String> requiredAnswers(JsonNode answers) {
        if (!answers.isObject() || answers.isEmpty()) {
            throw new IllegalArgumentException("answers 必须包含当前全部澄清答案");
        }
        Map<String, String> result = new LinkedHashMap<>();
        answers.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("澄清答案必须是文本");
            }
            String answer = entry.getValue().asText().strip();
            if (answer.isBlank() || answer.length() > 1000) {
                throw new IllegalArgumentException(
                        "澄清答案不能为空且不能超过 1000 字");
            }
            result.put(entry.getKey(), answer);
        });
        return Map.copyOf(result);
    }

    private StudyType studyType(String value) {
        return switch (value) {
            case "CROSS_SECTIONAL" -> StudyType.CROSS_SECTIONAL;
            case "COHORT" -> StudyType.COHORT;
            case "CASE_CONTROL" -> StudyType.CASE_CONTROL;
            default -> throw new IllegalArgumentException(
                    "studyType 只支持横断面、队列或病例对照研究");
        };
    }

    private boolean requiredTrue(
            JsonNode body,
            String field,
            String message) {
        if (!body.path(field).isBoolean()
                || !body.path(field).asBoolean()) {
            throw new IllegalArgumentException(message);
        }
        return true;
    }

    private void submitReview(
            WorkspaceReadModelService.WorkspaceContext context,
            JsonNode body,
            Responsibility responsibility) {
        reviews.decide(
                requireTask(context).id(),
                responsibility,
                decision(requiredText(body, "decision", 40)),
                requiredText(body, "summary", 2000),
                requiredPositiveLong(body, "reviewVersion"));
    }

    private CommentType commentType(String value) {
        try {
            return CommentType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "commentType 不受支持");
        }
    }

    private Responsibility responsibility(String value) {
        try {
            return Responsibility.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "responsibility 不受支持");
        }
    }

    private Decision decision(String value) {
        try {
            return Decision.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "decision 只支持通过或退回修订");
        }
    }

    private Long requiredPositiveLong(
            JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.canConvertToLong()
                || value.asLong() < 0) {
            throw new IllegalArgumentException(
                    field + " 必须是非负整数");
        }
        return value.asLong();
    }

    private String taskIdempotencyKey(String workspaceKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(workspaceKey.getBytes(StandardCharsets.UTF_8));
            return "v2-" + HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 Agent 任务幂等键", exception);
        }
    }

    private BusinessException versionConflict() {
        return BusinessException.conflict(
                "READ_MODEL_VERSION_CONFLICT",
                "课题状态已变化，请刷新后重试");
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("V2 动作响应序列化失败", exception);
        }
    }
}
