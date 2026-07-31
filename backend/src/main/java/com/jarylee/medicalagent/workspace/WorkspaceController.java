package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarylee.medicalagent.workspace.WorkspaceModels.Envelope;
import com.jarylee.medicalagent.workspace.WorkspaceModels.IdeaDirectionView;
import com.jarylee.medicalagent.workspace.WorkspaceModels.Page;
import com.jarylee.medicalagent.workspace.WorkspaceModels.StageView;
import com.jarylee.medicalagent.workspace.WorkspaceModels.TodoItem;
import com.jarylee.medicalagent.workspace.WorkspaceModels.WorkspaceSummary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/research")
public class WorkspaceController {
    private static final Pattern IF_MATCH =
            Pattern.compile("^\"rmv-([1-9][0-9]*)\"$");

    private final WorkspaceReadModelService readModels;
    private final WorkspaceArtifactReadService artifacts;
    private final WorkspaceActionService actions;
    private final WorkspaceModelActionService modelActions;
    private final WorkspaceModelGovernanceService modelGovernance;
    private final WorkspaceEventService events;
    private final WorkspaceModelUsageService modelUsage;

    public WorkspaceController(
            WorkspaceReadModelService readModels,
            WorkspaceArtifactReadService artifacts,
            WorkspaceActionService actions,
            WorkspaceModelActionService modelActions,
            WorkspaceModelGovernanceService modelGovernance,
            WorkspaceEventService events,
            WorkspaceModelUsageService modelUsage) {
        this.readModels = readModels;
        this.artifacts = artifacts;
        this.actions = actions;
        this.modelActions = modelActions;
        this.modelGovernance = modelGovernance;
        this.events = events;
        this.modelUsage = modelUsage;
    }

    @GetMapping("/workspace/projects")
    public Envelope<Page<WorkspaceSummary>> projects(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        return readModels.listProjects(limit, cursor);
    }

    @GetMapping("/projects/{projectKey}/workspace-summary")
    public Envelope<WorkspaceSummary> summary(
            @PathVariable String projectKey) {
        return readModels.summary(projectKey);
    }

    @GetMapping("/projects/{projectKey}/stages")
    public Envelope<List<StageView>> stages(
            @PathVariable String projectKey) {
        return readModels.stages(projectKey);
    }

    @GetMapping("/todos")
    public Envelope<Page<TodoItem>> todos(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        requireOpenStatus(status);
        return readModels.todos(limit, cursor);
    }

    @GetMapping("/projects/{projectKey}/todos")
    public Envelope<List<TodoItem>> projectTodos(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "OPEN") String status) {
        requireOpenStatus(status);
        return readModels.projectTodos(projectKey);
    }

    @GetMapping("/projects/{projectKey}/idea-direction")
    public Envelope<IdeaDirectionView> ideaDirection(
            @PathVariable String projectKey) {
        return readModels.ideaDirection(projectKey);
    }

    @GetMapping("/projects/{projectKey}/evidence")
    public Envelope<WorkspaceArtifactModels.ArtifactSectionView> evidence(
            @PathVariable String projectKey) {
        return artifacts.evidence(projectKey);
    }

    @GetMapping("/projects/{projectKey}/design")
    public Envelope<WorkspaceArtifactModels.ArtifactSectionView> design(
            @PathVariable String projectKey) {
        return artifacts.design(projectKey);
    }

    @GetMapping("/projects/{projectKey}/protocol")
    public Envelope<WorkspaceArtifactModels.ArtifactSectionView> protocol(
            @PathVariable String projectKey) {
        return artifacts.protocol(projectKey);
    }

    @GetMapping("/projects/{projectKey}/statistics")
    public Envelope<WorkspaceArtifactModels.ArtifactSectionView> statistics(
            @PathVariable String projectKey) {
        return artifacts.statistics(projectKey);
    }

    @GetMapping("/projects/{projectKey}/quality")
    public Envelope<WorkspaceArtifactModels.ArtifactSectionView> quality(
            @PathVariable String projectKey) {
        return artifacts.quality(projectKey);
    }

    @GetMapping("/projects/{projectKey}/internal-review")
    public Envelope<WorkspaceArtifactModels.ArtifactSectionView> internalReview(
            @PathVariable String projectKey) {
        return artifacts.internalReview(projectKey);
    }

    @GetMapping("/projects/{projectKey}/draft-export")
    public Envelope<WorkspaceArtifactModels.ArtifactSectionView> draftExport(
            @PathVariable String projectKey) {
        return artifacts.draftExport(projectKey);
    }

    @GetMapping("/projects/{projectKey}/protocol/model-candidates")
    public Envelope<List<WorkspaceModelGovernanceService.CandidateView>>
    modelCandidates(@PathVariable String projectKey) {
        return modelGovernance.candidateEnvelope(projectKey);
    }

    @GetMapping("/projects/{projectKey}/protocol/model-reviews")
    public Envelope<List<WorkspaceModelGovernanceService.ReviewView>>
    modelReviews(@PathVariable String projectKey) {
        return modelGovernance.reviewEnvelope(projectKey);
    }

    @GetMapping("/projects/{projectKey}/design/model-advice")
    public Envelope<List<WorkspaceModelGovernanceService.DesignAdviceView>>
    designModelAdvice(@PathVariable String projectKey) {
        return modelGovernance.designAdviceEnvelope(projectKey);
    }

    @GetMapping("/projects/{projectKey}/model-usage")
    public Envelope<WorkspaceModelUsageService.ModelUsageView> modelUsage(
            @PathVariable String projectKey) {
        return modelUsage.usage(projectKey);
    }

    @GetMapping("/projects/{projectKey}/model-governance")
    public Envelope<WorkspaceModelUsageService.ModelGovernanceView>
    modelGovernance(@PathVariable String projectKey) {
        return modelUsage.governance(projectKey);
    }

    @PutMapping("/projects/{projectKey}/model-governance/budget")
    public Envelope<WorkspaceModelUsageService.ModelGovernanceView>
    updateModelBudget(
            @PathVariable String projectKey,
            @RequestBody WorkspaceModelUsageService.BudgetUpdateRequest request) {
        return modelUsage.updateBudget(projectKey, request);
    }

    @GetMapping("/projects/{projectKey}/exports/{exportKey}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable String projectKey,
            @PathVariable String exportKey) {
        var file = artifacts.download(projectKey, exportKey);
        return ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-SHA256", file.contentSha256())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @PostMapping("/projects/{projectKey}/actions/{actionCode}")
    public Envelope<WorkspaceSummary> execute(
            @PathVariable String projectKey,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody(required = false) JsonNode body) {
        long expectedVersion = parseIfMatch(ifMatch);
        if (modelActions.supports(actionCode)) {
            return modelActions.execute(
                    projectKey,
                    actionCode,
                    idempotencyKey,
                    expectedVersion,
                    body);
        }
        return actions.execute(
                projectKey, actionCode, idempotencyKey, expectedVersion, body);
    }

    @GetMapping(
            value = "/projects/{projectKey}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String projectKey,
            @RequestHeader(
                    name = "Last-Event-ID",
                    required = false) String lastEventId) {
        return events.subscribe(projectKey, parseLastEventId(lastEventId));
    }

    private long parseIfMatch(String value) {
        Matcher matcher = IF_MATCH.matcher(
                value == null ? "" : value.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "If-Match 必须使用 \"rmv-<版本>\" 格式");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match 版本超出范围");
        }
    }

    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(
                        "Last-Event-ID 必须为非负整数");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Last-Event-ID 必须为非负整数");
        }
    }

    private void requireOpenStatus(String status) {
        if (!"OPEN".equals(status)) {
            throw new IllegalArgumentException(
                    "当前切片的待办 status 只支持 OPEN");
        }
    }
}
