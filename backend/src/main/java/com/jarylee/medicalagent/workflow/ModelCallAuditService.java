package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ModelInvocation;
import com.jarylee.medicalagent.agent.model.ModelRoute;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;
import com.jarylee.medicalagent.safety.ExternalModelInputGuard;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ModelCallAuditService {
    private final ModelCallAuditRepository repository;
    private final ObjectMapper json;
    private final Clock clock;
    private final ExternalModelInputGuard inputGuard;
    private final ModelCostCalculator costs;
    private final ModelBudgetService budget;

    @Autowired
    public ModelCallAuditService(
            ModelCallAuditRepository repository, ObjectMapper json, Clock clock,
            ExternalModelInputGuard inputGuard, ModelCostCalculator costs,
            ModelBudgetService budget) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        this.inputGuard = inputGuard;
        this.costs = costs;
        this.budget = budget;
    }

    public ModelCallAuditService(
            ModelCallAuditRepository repository, ObjectMapper json, Clock clock,
            ExternalModelInputGuard inputGuard) {
        this(repository, json, clock, inputGuard, new ModelCostCalculator());
    }

    public ModelCallAuditService(
            ModelCallAuditRepository repository, ObjectMapper json, Clock clock,
            ExternalModelInputGuard inputGuard, ModelCostCalculator costs) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        this.inputGuard = inputGuard;
        this.costs = costs;
        this.budget = null;
    }

    public AuditedAnalysis invokeAnalysis(
            AgentWorkflowRepository.TaskData task,
            int attemptNo,
            VersionedPrompt prompt,
            ModelRoute route,
            String input,
            Supplier<ModelInvocation<AnalysisResult>> invocation) {
        var result = invokeStructured(
                task,
                attemptNo,
                prompt,
                route,
                "research-model-input/v1",
                "research-analysis/v1",
                input,
                invocation);
        return new AuditedAnalysis(result.modelCallId(), result.output());
    }

    public <T> AuditedModelResult<T> invokeStructured(
            AgentWorkflowRepository.TaskData task,
            int attemptNo,
            VersionedPrompt prompt,
            ModelRoute route,
            String inputSchemaVersion,
            String outputSchemaVersion,
            String input,
            Supplier<ModelInvocation<T>> invocation) {
        UUID callId = UUID.randomUUID();
        var startedAt = clock.instant();
        String controlledInput = inputGuard.requireAllowed(input);
        String inputHash = sha256(controlledInput);
        String promptHash = sha256(prompt.template());
        String renderedPrompt = prompt.template().replace("${input}", controlledInput);
        String initialCostStatus = route.pricing().priced()
                ? "USAGE_UNAVAILABLE" : "UNPRICED";
        var draft = new ModelCallAuditRepository.ModelCallData(
                callId, task.hospitalId(), task.projectId(), task.id(),
                prompt.stepCode(), attemptNo,
                route.model().provider(), route.model().modelName(), prompt.version(),
                inputSchemaVersion, outputSchemaVersion,
                inputHash, null,
                inputSnapshot(
                        task.id(), prompt, route, controlledInput,
                        inputHash, promptHash, renderedPrompt,
                        inputSchemaVersion, outputSchemaVersion),
                null, null, null, "REQUESTED", null, null,
                startedAt, null, startedAt.plus(Duration.ofDays(90)),
                startedAt.plus(Duration.ofDays(365L * 3)),
                route.logicalModelType().name(),
                route.policyVersion(),
                route.routeReason(),
                null,
                "NOT_AVAILABLE",
                null, null, null, null,
                route.pricing().version(),
                route.pricing().currency(),
                null,
                initialCostStatus,
                null);
        if (budget == null) {
            repository.start(draft);
        } else {
            budget.reserveAndStart(
                    task,
                    route,
                    controlledInput.codePointCount(
                            0, controlledInput.length()),
                    prompt.template().codePointCount(
                            0, prompt.template().length()),
                    draft);
        }
        boolean auditCompleted = false;
        try {
            ModelInvocation<T> invoked = invocation.get();
            T output = invoked.output();
            String serialized = write(output);
            Instant completedAt = clock.instant();
            var cost = costs.calculate(route.pricing(), invoked.usage());
            repository.succeed(
                    callId,
                    new ModelCallAuditRepository.CompletionData(
                            sha256(serialized),
                            outputSnapshot(
                                    output, outputSchemaVersion, invoked,
                                    startedAt, completedAt),
                            invoked.providerRequestId(),
                            invoked.usage().source(),
                            invoked.usage().inputTokens(),
                            invoked.usage().cachedInputTokens(),
                            invoked.usage().outputTokens(),
                            invoked.usage().totalTokens(),
                            cost.estimatedCostMicros(),
                            cost.status()),
                    completedAt);
            auditCompleted = true;
            if (budget != null) {
                budget.verifyAfterCompletion(task, route, cost);
            }
            return new AuditedModelResult<>(callId, output);
        } catch (RuntimeException exception) {
            if (!auditCompleted) {
                repository.fail(
                        callId,
                        errorCode(exception),
                        safeMessage(exception),
                        clock.instant());
            }
            throw exception;
        }
    }

    private String inputSnapshot(
            UUID taskId, VersionedPrompt prompt, ModelRoute route,
            String controlledInput, String inputHash, String promptHash,
            String renderedPrompt,
            String inputSchemaVersion,
            String outputSchemaVersion) {
        var snapshot = json.createObjectNode();
        snapshot.put("schemaVersion", "model-input-snapshot/v3");
        snapshot.put("logicalModelType", route.logicalModelType().name());
        snapshot.put("routePolicyVersion", route.policyVersion());
        snapshot.put("routeReason", route.routeReason());
        snapshot.put("provider", route.model().provider());
        snapshot.put("modelName", route.model().modelName());
        snapshot.put("promptCode", prompt.stepCode());
        snapshot.put("promptVersion", prompt.version());
        snapshot.put("promptSha256", promptHash);
        snapshot.put("renderedPromptSha256", sha256(renderedPrompt));
        snapshot.put("promptTemplate", prompt.template());
        snapshot.put("characterCount",
                controlledInput.codePointCount(0, controlledInput.length()));
        snapshot.put("sha256", inputHash);
        snapshot.put("safetyAssessment", "PASSED_EXTERNAL_MODEL_INPUT_GUARD");
        snapshot.put("inputSnapshotRef",
                "database:ai_agent_task/" + taskId
                        + "+ai_agent_clarification_round");
        snapshot.set("replaySources", json.valueToTree(List.of(
                "ai_agent_task.input_json",
                "ai_agent_clarification_round.answers_json")));
        snapshot.set("modelParameters", json.valueToTree(Map.of(
                "captureStatus", "PROVIDER_ADAPTER_FIXED_PARAMETERS",
                "inputSchema", inputSchemaVersion,
                "responseSchema", outputSchemaVersion)));
        if (route.pricing().priced()) {
            snapshot.set("pricing", json.valueToTree(Map.of(
                    "version", route.pricing().version(),
                    "currency", route.pricing().currency())));
        }
        return write(snapshot);
    }

    private String outputSnapshot(
            Object output,
            String outputSchemaVersion,
            ModelInvocation<?> invocation,
            Instant startedAt,
            Instant completedAt) {
        var snapshot = json.createObjectNode();
        snapshot.put("schemaVersion", "model-output-snapshot/v3");
        snapshot.put("outputSchemaVersion", outputSchemaVersion);
        snapshot.put("finishReason", invocation.finishReason());
        snapshot.put("durationMs",
                Math.max(0L, Duration.between(startedAt, completedAt).toMillis()));
        snapshot.put("retryCount", 0);
        if (invocation.providerRequestId() == null) {
            snapshot.putNull("providerRequestId");
        } else {
            snapshot.put("providerRequestId", invocation.providerRequestId());
        }
        snapshot.set("usage", json.valueToTree(invocation.usage()));
        snapshot.set("controlledOutput", json.valueToTree(output));
        return write(snapshot);
    }

    private String errorCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName()
                .replaceAll("[^A-Za-z0-9_]", "_")
                .toUpperCase();
        return name.length() <= 80 ? name : name.substring(0, 80);
    }

    private String safeMessage(RuntimeException exception) {
        return "模型调用失败；原始错误信息不写入审计库";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("模型调用哈希失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("模型调用审计序列化失败", exception);
        }
    }

    public record AuditedAnalysis(UUID modelCallId, AnalysisResult output) {}

    public record AuditedModelResult<T>(UUID modelCallId, T output) {}
}
