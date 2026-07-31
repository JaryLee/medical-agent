package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.workspace.WorkspaceModels.Envelope;
import com.jarylee.medicalagent.workspace.WorkspaceModels.WorkspaceSummary;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class WorkspaceModelActionService {
    private static final Set<String> SUPPORTED = Set.of(
            "GENERATE_PROTOCOL_SECTION_CANDIDATE",
            "REVIEW_PROTOCOL_SECTION_CANDIDATE",
            "APPLY_PROTOCOL_SECTION_CANDIDATE",
            "REQUEST_DESIGN_MODEL_ADVICE");
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("^[^\\p{Cntrl}]{16,128}$");

    private final WorkspaceReadModelService readModels;
    private final WorkspaceRepository workspace;
    private final WorkspaceModelGovernanceService governance;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkspaceModelActionService(
            WorkspaceReadModelService readModels,
            WorkspaceRepository workspace,
            WorkspaceModelGovernanceService governance,
            ObjectMapper json,
            Clock clock) {
        this.readModels = readModels;
        this.workspace = workspace;
        this.governance = governance;
        this.json = json;
        this.clock = clock;
    }

    public boolean supports(String actionCode) {
        return SUPPORTED.contains(actionCode);
    }

    public Envelope<WorkspaceSummary> execute(
            String projectKey,
            String actionCode,
            String idempotencyKey,
            long expectedReadModelVersion,
            JsonNode body) {
        validate(actionCode, idempotencyKey, expectedReadModelVersion);
        var context = readModels.resolve(projectKey);
        if (!context.canEdit()) {
            throw BusinessException.forbidden(
                    "当前账号只有查看权限，不能执行模型辅助动作");
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

        var before = readModels.aggregate(context);
        if (before.cursor().readModelVersion() != expectedReadModelVersion) {
            throw versionConflict();
        }
        boolean allowed = before.summary().allowedActions().stream()
                .anyMatch(action -> action.enabled()
                        && action.code().equals(actionCode));
        if (!allowed) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_NOT_ALLOWED",
                    "当前课题状态或账号权限不允许执行该动作");
        }
        UUID commandId = UUID.randomUUID();
        var reservation = workspace.reserve(new WorkspaceRepository.CommandDraft(
                commandId,
                context.actor().hospitalId(),
                context.project().id(),
                context.actor().userId(),
                actionCode,
                idempotencyKey,
                requestHash,
                expectedReadModelVersion,
                clock.instant()));
        if (reservation.versionConflict()) throw versionConflict();
        if (!reservation.acquired()) {
            return replay(reservation.existing(), requestHash);
        }

        try {
            switch (actionCode) {
                case "GENERATE_PROTOCOL_SECTION_CANDIDATE" ->
                        governance.generateCandidate(
                                context, requiredText(
                                        effectiveBody, "sectionKey", 64));
                case "REVIEW_PROTOCOL_SECTION_CANDIDATE" ->
                        governance.reviewCandidate(
                                context, requiredText(
                                        effectiveBody, "candidateKey", 64));
                case "APPLY_PROTOCOL_SECTION_CANDIDATE" ->
                        governance.applyCandidate(
                                context,
                                requiredText(effectiveBody, "candidateKey", 64),
                                requiredPositiveLong(
                                        effectiveBody,
                                        "expectedCandidateVersion"));
                case "REQUEST_DESIGN_MODEL_ADVICE" ->
                        governance.adviseObservationalDesign(context);
                default -> throw new IllegalArgumentException(
                        "不支持的模型辅助动作");
            }
            var result = readModels.aggregate(context);
            Envelope<WorkspaceSummary> response = new Envelope<>(
                    result.summary(),
                    new WorkspaceModels.ResponseMeta(
                            result.cursor().readModelVersion(),
                            clock.instant(),
                            result.cursor().latestEventId()));
            workspace.completeCommand(
                    context.actor().hospitalId(),
                    commandId,
                    result.cursor().readModelVersion(),
                    write(response),
                    clock.instant());
            return response;
        } catch (RuntimeException exception) {
            var current = readModels.aggregate(context);
            workspace.completeCommand(
                    context.actor().hospitalId(),
                    commandId,
                    current.cursor().readModelVersion(),
                    write(Map.of(
                            "failed", true,
                            "code", "MODEL_ACTION_FAILED",
                            "message", "模型辅助动作失败；请检查输入后使用新幂等键重试")),
                    clock.instant());
            throw exception;
        }
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
                    "相同模型辅助动作正在处理，请稍后使用同一幂等键重试");
        }
        try {
            JsonNode response = json.readTree(command.responseJson());
            if (response.path("failed").asBoolean(false)) {
                throw BusinessException.conflict(
                        "MODEL_ACTION_PREVIOUSLY_FAILED",
                        "上次模型辅助动作失败；请检查输入后使用新幂等键重试");
            }
            return json.readValue(
                    command.responseJson(),
                    new TypeReference<Envelope<WorkspaceSummary>>() {});
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("模型辅助动作幂等响应损坏", exception);
        }
    }

    private void validate(
            String actionCode,
            String idempotencyKey,
            long expectedReadModelVersion) {
        if (!supports(actionCode)) {
            throw new IllegalArgumentException("不支持的模型辅助动作");
        }
        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key 必须是 16 到 128 个非控制字符");
        }
        if (expectedReadModelVersion < 1) {
            throw new IllegalArgumentException(
                    "If-Match 读模型版本必须大于 0");
        }
    }

    private String requiredText(JsonNode body, String field, int maxLength) {
        String value = body.path(field).asText("").strip();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " 不能为空且不能超过 " + maxLength + " 字");
        }
        return value;
    }

    private long requiredPositiveLong(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()
                || node.asLong() < 0) {
            throw new IllegalArgumentException(field + " 必须是非负整数");
        }
        return node.asLong();
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
            throw new IllegalStateException("模型辅助动作响应序列化失败", exception);
        }
    }
}
