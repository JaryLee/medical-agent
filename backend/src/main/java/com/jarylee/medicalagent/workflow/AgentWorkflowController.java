package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.common.ApiResponse;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/tasks")
public class AgentWorkflowController {
    private final AgentWorkflowService service;

    public AgentWorkflowController(AgentWorkflowService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<AgentWorkflowService.TaskView> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTaskRequest request) {
        return ApiResponse.ok(service.create(request.projectId(), request.idea(), idempotencyKey));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AgentWorkflowService.TaskView> get(@PathVariable UUID taskId) {
        return ApiResponse.ok(service.get(taskId));
    }

    @GetMapping
    public ApiResponse<java.util.List<AgentWorkflowService.TaskView>> list(
            @RequestParam UUID projectId) {
        return ApiResponse.ok(service.list(projectId));
    }

    @PostMapping("/{taskId}/confirm-direction")
    public ApiResponse<AgentWorkflowService.TaskView> confirm(
            @PathVariable UUID taskId, @Valid @RequestBody ConfirmDirectionRequest request) {
        return ApiResponse.ok(service.confirm(
                taskId, request.directionId(),
                request.candidateSetId(), request.candidateSetHash()));
    }

    @PostMapping("/{taskId}/clarifications")
    public ApiResponse<AgentWorkflowService.TaskView> submitClarifications(
            @PathVariable UUID taskId, @Valid @RequestBody ClarificationRequest request) {
        return ApiResponse.ok(service.submitClarifications(taskId, request.answers()));
    }

    @GetMapping("/{taskId}/clarifications")
    public ApiResponse<java.util.List<AgentWorkflowService.ClarificationRoundView>>
            clarificationHistory(@PathVariable UUID taskId) {
        return ApiResponse.ok(service.clarificationHistory(taskId));
    }

    @PostMapping("/{taskId}/confirm-search-strategy")
    public ApiResponse<AgentWorkflowService.TaskView> confirmSearchStrategy(
            @PathVariable UUID taskId,
            @Valid @RequestBody ConfirmSearchStrategyRequest request) {
        return ApiResponse.ok(service.confirmSearchStrategy(taskId, request.pubmedQuery()));
    }

    @PostMapping("/{taskId}/confirm-observational-design")
    public ApiResponse<AgentWorkflowService.TaskView> confirmObservationalDesign(
            @PathVariable UUID taskId,
            @Valid @RequestBody ConfirmObservationalDesignRequest request) {
        return ApiResponse.ok(service.confirmObservationalDesign(
                taskId,
                request.studyType(),
                request.primaryOutcome(),
                request.authorizeProtocolGeneration()));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<AgentWorkflowService.TaskView> cancel(@PathVariable UUID taskId) {
        return ApiResponse.ok(service.cancel(taskId));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<AgentWorkflowService.TaskView> retry(@PathVariable UUID taskId) {
        return ApiResponse.ok(service.retry(taskId));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable UUID taskId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long after = parseLastEventId(lastEventId);
        return service.events(taskId, after);
    }

    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new IllegalArgumentException("Last-Event-ID 不能为负数");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID 必须为非负整数");
        }
    }

    public record CreateTaskRequest(
            @NotNull UUID projectId,
            @NotBlank @Size(max = 2000) String idea) {}

    public record ConfirmDirectionRequest(
            @NotBlank String directionId,
            @NotNull UUID candidateSetId,
            @NotBlank @Size(min = 64, max = 64) String candidateSetHash) {}
    public record ClarificationRequest(@NotEmpty Map<String, String> answers) {}
    public record ConfirmSearchStrategyRequest(
            @NotBlank @Size(max = 4000) String pubmedQuery) {}
    public record ConfirmObservationalDesignRequest(
            @NotNull StudyType studyType,
            @NotBlank @Size(max = 1000) String primaryOutcome,
            @NotNull Boolean authorizeProtocolGeneration) {}
}
