package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.research.ProjectKey;
import com.jarylee.medicalagent.research.ProjectMemberRepository;
import com.jarylee.medicalagent.research.ProjectMemberRole;
import com.jarylee.medicalagent.research.ProjectRepository;
import com.jarylee.medicalagent.review.ExpertReviewRepository;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workspace.WorkspaceModels.AllowedAction;
import com.jarylee.medicalagent.workspace.WorkspaceModels.BlockedReason;
import com.jarylee.medicalagent.workspace.WorkspaceModels.ClarificationRound;
import com.jarylee.medicalagent.workspace.WorkspaceModels.DirectionCandidate;
import com.jarylee.medicalagent.workspace.WorkspaceModels.DirectionCandidateSet;
import com.jarylee.medicalagent.workspace.WorkspaceModels.Envelope;
import com.jarylee.medicalagent.workspace.WorkspaceModels.IdeaDirectionView;
import com.jarylee.medicalagent.workspace.WorkspaceModels.LabeledCode;
import com.jarylee.medicalagent.workspace.WorkspaceModels.Page;
import com.jarylee.medicalagent.workspace.WorkspaceModels.Progress;
import com.jarylee.medicalagent.workspace.WorkspaceModels.ResearchIdea;
import com.jarylee.medicalagent.workspace.WorkspaceModels.ResponseMeta;
import com.jarylee.medicalagent.workspace.WorkspaceModels.StageView;
import com.jarylee.medicalagent.workspace.WorkspaceModels.TodoItem;
import com.jarylee.medicalagent.workspace.WorkspaceModels.WorkspaceSummary;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceReadModelService {
    private static final String DEFAULT_DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批。"
                    + "当前内容不替代医学、统计、伦理或科研管理审核。";

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final AgentWorkflowRepository workflows;
    private final WorkspaceRepository workspace;
    private final ExpertReviewRepository reviews;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkspaceReadModelService(
            ProjectRepository projects,
            ProjectMemberRepository members,
            AgentWorkflowRepository workflows,
            WorkspaceRepository workspace,
            ExpertReviewRepository reviews,
            CurrentUserProvider currentUser,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.projects = projects;
        this.members = members;
        this.workflows = workflows;
        this.workspace = workspace;
        this.reviews = reviews;
        this.currentUser = currentUser;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public Envelope<Page<WorkspaceSummary>> listProjects(
            int limit, String cursor) {
        AuthenticatedUser actor = requireActor();
        int pageSize = pageSize(limit);
        List<WorkspaceSummary> summaries = projects.findVisible(
                        actor.hospitalId(), actor.userId(),
                        actor.hasRole(Role.HOSPITAL_ADMIN)).stream()
                .filter(project -> cursor == null
                        || project.projectKey().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(ProjectRepository.ProjectData::projectKey))
                .limit(pageSize + 1L)
                .map(project -> aggregate(context(actor, project)).summary())
                .toList();
        boolean more = summaries.size() > pageSize;
        List<WorkspaceSummary> items = more
                ? summaries.subList(0, pageSize) : summaries;
        String nextCursor = more
                ? items.get(items.size() - 1).projectKey() : null;
        return new Envelope<>(
                new Page<>(List.copyOf(items), nextCursor),
                aggregateMeta(actor, items));
    }

    public Envelope<WorkspaceSummary> summary(String projectKey) {
        WorkspaceContext context = resolve(projectKey);
        Aggregate aggregate = aggregate(context);
        return new Envelope<>(aggregate.summary(), meta(aggregate.cursor()));
    }

    public Envelope<List<StageView>> stages(String projectKey) {
        WorkspaceContext context = resolve(projectKey);
        Aggregate aggregate = aggregate(context);
        return new Envelope<>(aggregate.stages(), meta(aggregate.cursor()));
    }

    public Envelope<List<TodoItem>> projectTodos(String projectKey) {
        WorkspaceContext context = resolve(projectKey);
        Aggregate aggregate = aggregate(context);
        List<TodoItem> todos = aggregate.todo() == null
                ? List.of() : List.of(aggregate.todo());
        return new Envelope<>(todos, meta(aggregate.cursor()));
    }

    public Envelope<Page<TodoItem>> todos(
            int limit, String cursor) {
        AuthenticatedUser actor = requireActor();
        int pageSize = pageSize(limit);
        List<Aggregate> aggregates = projects.findVisible(
                        actor.hospitalId(), actor.userId(),
                        actor.hasRole(Role.HOSPITAL_ADMIN)).stream()
                .map(project -> aggregate(context(actor, project)))
                .toList();
        List<TodoItem> all = aggregates.stream()
                .filter(Aggregate::todoAssignedToActor)
                .map(Aggregate::todo)
                .filter(value -> value != null)
                .filter(value -> cursor == null
                        || value.todoKey().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(TodoItem::todoKey))
                .limit(pageSize + 1L)
                .toList();
        boolean more = all.size() > pageSize;
        List<TodoItem> items = more ? all.subList(0, pageSize) : all;
        String nextCursor = more
                ? items.get(items.size() - 1).todoKey() : null;
        ResponseMeta meta = aggregateMeta(
                actor, aggregates.stream().map(Aggregate::summary).toList());
        return new Envelope<>(
                new Page<>(List.copyOf(items), nextCursor), meta);
    }

    public Envelope<IdeaDirectionView> ideaDirection(String projectKey) {
        WorkspaceContext context = resolve(projectKey);
        Aggregate aggregate = aggregate(context);
        AgentWorkflowRepository.TaskData task = aggregate.task();
        if (task == null) {
            IdeaDirectionView view = new IdeaDirectionView(
                    context.project().projectKey(),
                    new LabeledCode("NOT_STARTED", "尚未提交研究构想"),
                    null, List.of(), List.of(), null,
                    aggregate.summary().allowedActions(), DEFAULT_DISCLAIMER);
            return new Envelope<>(view, meta(aggregate.cursor()));
        }

        JsonNode input = readTree(task.inputJson());
        JsonNode output = readTree(task.outputJson());
        List<String> currentQuestions = "STEP_03_ASK_CLARIFICATION"
                .equals(task.currentStep())
                ? strings(output == null
                        ? null : output.path("clarificationQuestions"))
                : List.of();
        List<ClarificationRound> history = workflows.findClarificationRounds(
                        context.actor().hospitalId(), task.id()).stream()
                .map(round -> new ClarificationRound(
                        round.roundNo(),
                        strings(readTree(round.questionsJson())),
                        stringMap(readTree(round.answersJson())),
                        round.submittedAt()))
                .toList();
        DirectionCandidateSet candidates = directionCandidates(task);
        String disclaimer = output != null
                && !output.path("disclaimer").asText().isBlank()
                ? output.path("disclaimer").asText()
                : DEFAULT_DISCLAIMER;
        IdeaDirectionView view = new IdeaDirectionView(
                context.project().projectKey(),
                workflowStatus(task),
                new ResearchIdea(input.path("idea").asText(), ideaStatus(task)),
                currentQuestions,
                history,
                candidates,
                aggregate.summary().allowedActions().stream()
                        .filter(action -> List.of(
                                "SUBMIT_CLARIFICATIONS",
                                "CONFIRM_RESEARCH_DIRECTION",
                                "CANCEL_RESEARCH_WORKFLOW",
                                "RETRY_RESEARCH_WORKFLOW")
                                .contains(action.code()))
                        .toList(),
                disclaimer);
        return new Envelope<>(view, meta(aggregate.cursor()));
    }

    WorkspaceContext resolve(String projectKey) {
        AuthenticatedUser actor = requireActor();
        if (!ProjectKey.isValid(projectKey)) {
            audit.record(actor, "WORKSPACE_PROJECT_NOT_FOUND",
                    "RESEARCH_PROJECT", "invalid");
            throw BusinessException.projectNotFound();
        }
        ProjectRepository.ProjectData project = projects.findVisibleByKey(
                        actor.hospitalId(), projectKey, actor.userId(),
                        actor.hasRole(Role.HOSPITAL_ADMIN))
                .orElseThrow(() -> {
                    audit.record(actor, "WORKSPACE_PROJECT_NOT_FOUND",
                            "RESEARCH_PROJECT", projectKey);
                    return BusinessException.projectNotFound();
                });
        return context(actor, project);
    }

    Aggregate aggregate(WorkspaceContext context) {
        AgentWorkflowRepository.TaskData task = workflows.findByProject(
                        context.actor().hospitalId(), context.project().id())
                .stream().findFirst().orElse(null);
        WorkspaceRepository.Cursor cursor = workspace.requireCursor(
                context.actor().hospitalId(), context.project().id(), clock.instant());
        List<StageView> stages = buildStages(context.project().projectKey(), task);
        StageView currentStage = currentStage(stages);
        LabeledCode businessStatus = businessStatus(task);
        AllowedAction primary = primaryAction(context, task, currentStage);
        List<AllowedAction> allowed = allowedActions(context, task, primary);
        List<BlockedReason> blocked = blockedReasons(task);
        int completed = task != null && "COMPLETED".equals(task.status())
                ? WorkspaceStageCatalog.STAGES.size()
                : stages.indexOf(currentStage);
        int total = WorkspaceStageCatalog.STAGES.size();
        Progress progress = new Progress(
                completed, total, (completed * 100) / total);
        TodoItem todo = todo(context, task, primary);
        WorkspaceSummary summary = new WorkspaceSummary(
                context.project().projectKey(),
                context.project().name(),
                businessStatus,
                currentStage,
                progress,
                primary,
                allowed,
                blocked,
                todo == null ? 0 : 1,
                cursor.updatedAt());
        boolean assigned = todo != null
                && (context.canEdit()
                || "CONTINUE_IN_LEGACY_WORKSPACE".equals(primary.code()));
        return new Aggregate(
                context, task, cursor, summary, stages, todo, assigned);
    }

    DirectionSelection resolveDirection(
            WorkspaceContext context, String directionKey) {
        AgentWorkflowRepository.TaskData task = workflows.findByProject(
                        context.actor().hospitalId(), context.project().id())
                .stream().findFirst()
                .orElseThrow(() -> BusinessException.conflict(
                        "PROJECT_ACTION_NOT_ALLOWED", "当前没有可确认的研究方向"));
        JsonNode output = readTree(task.outputJson());
        if (!"STEP_05_CONFIRM_DIRECTION".equals(task.currentStep())
                || !"WAITING_CONFIRMATION".equals(task.status())
                || output == null) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_NOT_ALLOWED", "当前不等待研究方向确认");
        }
        String candidateSetHash = output.path("candidateSetHash").asText();
        String candidateSetId = output.path("candidateSetId").asText();
        for (JsonNode direction : output.path("directions")) {
            String rawId = direction.path("id").asText();
            String publicKey = WorkspaceOpaqueKey.of(
                    "dir_", candidateSetHash, rawId);
            if (publicKey.equals(directionKey)) {
                return new DirectionSelection(
                        task, rawId, UUID.fromString(candidateSetId),
                        candidateSetHash);
            }
        }
        throw BusinessException.conflict(
                "PROJECT_ACTION_NOT_ALLOWED", "研究方向候选已变化，请刷新后重试");
    }

    AgentWorkflowRepository.TaskData latestTask(WorkspaceContext context) {
        return workflows.findByProject(
                        context.actor().hospitalId(), context.project().id())
                .stream().findFirst().orElse(null);
    }

    private WorkspaceContext context(
            AuthenticatedUser actor, ProjectRepository.ProjectData project) {
        ProjectMemberRole role = actor.hasRole(Role.HOSPITAL_ADMIN)
                ? null
                : members.findRole(
                        actor.hospitalId(), project.id(), actor.userId())
                .orElseThrow(BusinessException::projectNotFound);
        boolean canEdit = actor.hasRole(Role.HOSPITAL_ADMIN)
                || role == ProjectMemberRole.OWNER
                || role == ProjectMemberRole.EDITOR;
        return new WorkspaceContext(actor, project, role, canEdit);
    }

    private List<StageView> buildStages(
            String projectKey, AgentWorkflowRepository.TaskData task) {
        int currentIndex = task == null
                ? 0 : WorkspaceStageCatalog.indexForStep(task.currentStep());
        List<StageView> stages = new ArrayList<>();
        for (int index = 0; index < WorkspaceStageCatalog.STAGES.size(); index++) {
            var definition = WorkspaceStageCatalog.STAGES.get(index);
            String status;
            if (task != null && "COMPLETED".equals(task.status())) {
                status = "COMPLETED";
            } else if (index < currentIndex) {
                status = "COMPLETED";
            } else if (index > currentIndex) {
                status = "NOT_STARTED";
            } else {
                status = stageStatus(task);
            }
            stages.add(new StageView(
                    definition.code(),
                    definition.label(),
                    status,
                    stageSummary(definition.label(), status),
                    "/projects/" + projectKey + "/" + definition.routeSegment(),
                    "FAILED".equals(status)
                            ? List.of("WORKFLOW_FAILED")
                            : "BLOCKED".equals(status)
                            ? List.of("REVISION_REQUIRED")
                            : List.of(),
                    null));
        }
        return List.copyOf(stages);
    }

    private StageView currentStage(List<StageView> stages) {
        return stages.stream()
                .filter(stage -> !"COMPLETED".equals(stage.status())
                        && !"NOT_STARTED".equals(stage.status()))
                .findFirst()
                .orElseGet(() -> stages.stream()
                        .filter(stage -> "NOT_STARTED".equals(stage.status()))
                        .findFirst()
                        .orElse(stages.get(stages.size() - 1)));
    }

    private String stageStatus(AgentWorkflowRepository.TaskData task) {
        if (task == null) return "NOT_STARTED";
        return switch (task.status()) {
            case "WAITING_CONFIRMATION" -> "WAITING_USER";
            case "REVISION_REQUIRED", "CANCELLED" -> "BLOCKED";
            case "FAILED" -> "FAILED";
            case "COMPLETED" -> "COMPLETED";
            default -> "IN_PROGRESS";
        };
    }

    private String stageSummary(String label, String status) {
        return switch (status) {
            case "COMPLETED" -> label + "已完成。";
            case "WAITING_USER" -> label + "需要人工确认。";
            case "BLOCKED" -> label + "需要处理阻塞项。";
            case "FAILED" -> label + "处理失败，可在确认后重试。";
            case "IN_PROGRESS" -> label + "正在处理。";
            default -> label + "尚未开始。";
        };
    }

    private LabeledCode businessStatus(AgentWorkflowRepository.TaskData task) {
        if (task == null) return new LabeledCode("DRAFT", "草稿");
        if ("FAILED".equals(task.status())) {
            return new LabeledCode("FAILED", "处理失败");
        }
        if ("COMPLETED".equals(task.status())) {
            return new LabeledCode("COMPLETED", "科研草案已导出");
        }
        if ("REVISION_REQUIRED".equals(task.status())) {
            return new LabeledCode("REVISION_REQUIRED", "需修订");
        }
        if ("STEP_17_WAIT_EXPERT_REVIEW".equals(task.currentStep())) {
            return new LabeledCode("WAITING_REVIEW", "等待内部审核");
        }
        if ("STEP_18_EXPORT_DOCUMENT".equals(task.currentStep())) {
            return new LabeledCode("APPROVED", "内部审核完成");
        }
        return new LabeledCode("IN_PROGRESS", "编制中");
    }

    private AllowedAction primaryAction(
            WorkspaceContext context,
            AgentWorkflowRepository.TaskData task,
            StageView currentStage) {
        if (!context.canEdit()) {
            if (task != null
                    && "STEP_17_WAIT_EXPERT_REVIEW".equals(task.currentStep())
                    && context.actor().hasRole(Role.EXPERT)) {
                return action(
                        "ADD_INTERNAL_REVIEW_COMMENT", "填写内部审核意见", true,
                        null, null,
                        "/projects/" + context.project().projectKey() + "/review");
            }
            return action(
                    "VIEW_PROJECT", "查看课题", false,
                    "READ_ONLY", "当前账号只有查看权限。",
                    currentStage.targetRoute());
        }
        if (task == null || "CANCELLED".equals(task.status())) {
            return action(
                    "START_RESEARCH_IDEA", "提交研究构想", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/idea");
        }
        if ("FAILED".equals(task.status())) {
            return action(
                    "RETRY_RESEARCH_WORKFLOW", "重试当前阶段", true,
                    null, null, currentStage.targetRoute());
        }
        if ("QUEUED".equals(task.status()) || "RUNNING".equals(task.status())) {
            return action(
                    "WAIT_FOR_PROCESSING", "正在处理", false,
                    "PROCESSING", "系统正在处理，请稍后刷新。",
                    currentStage.targetRoute());
        }
        if ("WAITING_CONFIRMATION".equals(task.status())) {
            if ("STEP_03_ASK_CLARIFICATION".equals(task.currentStep())) {
                return action(
                        "SUBMIT_CLARIFICATIONS", "补充研究信息", true,
                        null, null,
                        "/projects/" + context.project().projectKey() + "/idea");
            }
            if ("STEP_05_CONFIRM_DIRECTION".equals(task.currentStep())) {
                return action(
                        "CONFIRM_RESEARCH_DIRECTION", "确认研究方向", true,
                        null, null,
                        "/projects/" + context.project().projectKey() + "/direction");
            }
            if ("STEP_07_BUILD_SEARCH_STRATEGY".equals(task.currentStep())) {
                return action(
                        "CONFIRM_SEARCH_STRATEGY", "确认文献检索策略", true,
                        null, null,
                        "/projects/" + context.project().projectKey() + "/evidence");
            }
            if ("STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN"
                    .equals(task.currentStep())) {
                return action(
                        "CONFIRM_OBSERVATIONAL_DESIGN", "确认研究设计", true,
                        null, null,
                        "/projects/" + context.project().projectKey() + "/design");
            }
            if ("STEP_17_WAIT_EXPERT_REVIEW".equals(task.currentStep())) {
                var review = reviews.findByAgentTask(
                        context.actor().hospitalId(), task.id());
                if (context.actor().hasRole(Role.EXPERT)
                        && review.map(value ->
                        "WAITING_EXPERT_REVIEW".equals(value.status()))
                        .orElse(false)) {
                    return action(
                            "ADD_INTERNAL_REVIEW_COMMENT", "填写内部审核意见", true,
                            null, null,
                            "/projects/" + context.project().projectKey() + "/review");
                }
                if (context.memberRole() == ProjectMemberRole.OWNER
                        && review.map(value ->
                        "EXPERT_APPROVED".equals(value.status()))
                        .orElse(false)) {
                    return action(
                            "CONFIRM_INTERNAL_REVIEW", "负责人确认内部审核", true,
                            null, null,
                            "/projects/" + context.project().projectKey() + "/review");
                }
                return action(
                        "WAIT_FOR_INTERNAL_REVIEW", "等待内部审核", false,
                        "INTERNAL_REVIEW_PENDING",
                        "医学与统计审核尚未全部通过。",
                        "/projects/" + context.project().projectKey() + "/review");
            }
            if ("STEP_18_EXPORT_DOCUMENT".equals(task.currentStep())) {
                if (context.memberRole() == ProjectMemberRole.OWNER) {
                    return action(
                            "EXPORT_RESEARCH_DRAFT", "导出科研草案", true,
                            null, null,
                            "/projects/" + context.project().projectKey() + "/export");
                }
                return action(
                        "VIEW_INTERNAL_REVIEW", "查看已确认内容", false,
                        "PROJECT_OWNER_REQUIRED",
                        "只有课题负责人可以确认导出。",
                        "/projects/" + context.project().projectKey() + "/export");
            }
            return action(
                    "CONTINUE_IN_LEGACY_WORKSPACE", "在旧版继续当前阶段", true,
                    null, null, "/workspace/legacy");
        }
        if ("REVISION_REQUIRED".equals(task.status())) {
            return action(
                    "UPDATE_PROTOCOL_SECTION", "修订研究方案", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/protocol");
        }
        if ("COMPLETED".equals(task.status())) {
            return action(
                    "VIEW_RESEARCH_DRAFT", "查看科研草案", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/export");
        }
        return action(
                "CONTINUE_IN_LEGACY_WORKSPACE", "在旧版继续当前阶段", true,
                null, null, "/workspace/legacy");
    }

    private List<AllowedAction> allowedActions(
            WorkspaceContext context,
            AgentWorkflowRepository.TaskData task,
            AllowedAction primary) {
        List<AllowedAction> actions = new ArrayList<>();
        if (primary.enabled()) actions.add(primary);
        if (task != null
                && "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN".equals(
                task.currentStep())
                && "WAITING_CONFIRMATION".equals(task.status())
                && context.canEdit()) {
            actions.add(action(
                    "REQUEST_DESIGN_MODEL_ADVICE",
                    "获取模型辅助设计建议", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/design"));
        }
        if (task != null
                && "STEP_17_WAIT_EXPERT_REVIEW".equals(task.currentStep())
                && "REVISION_REQUIRED".equals(task.status())
                && context.canEdit()) {
            actions.add(action(
                    "REGENERATE_PROTOCOL_SECTION", "恢复章节确定性初稿", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/protocol"));
            actions.add(action(
                    "GENERATE_PROTOCOL_SECTION_CANDIDATE", "生成单章模型候选", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/protocol"));
            actions.add(action(
                    "REVIEW_PROTOCOL_SECTION_CANDIDATE", "执行独立模型辅助复核", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/protocol"));
            actions.add(action(
                    "APPLY_PROTOCOL_SECTION_CANDIDATE", "采纳已复核的模型候选", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/protocol"));
            actions.add(action(
                    "SUBMIT_PROTOCOL_REVISION", "重新提交方案审核", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/protocol"));
        }
        if (task != null
                && "STEP_17_WAIT_EXPERT_REVIEW".equals(task.currentStep())) {
            var review = reviews.findByAgentTask(
                    context.actor().hospitalId(), task.id());
            if (context.actor().hasRole(Role.EXPERT)
                    && review.map(value ->
                    "WAITING_EXPERT_REVIEW".equals(value.status()))
                    .orElse(false)) {
                actions.add(action(
                        "SUBMIT_MEDICAL_REVIEW", "提交医学审核决定", true,
                        null, null,
                        "/projects/" + context.project().projectKey() + "/review"));
                actions.add(action(
                        "SUBMIT_STATISTICAL_REVIEW", "提交统计审核决定", true,
                        null, null,
                        "/projects/" + context.project().projectKey() + "/review"));
            }
        }
        if (context.canEdit() && task != null
                && List.of("QUEUED", "RUNNING", "WAITING_CONFIRMATION")
                .contains(task.status())) {
            actions.add(action(
                    "CANCEL_RESEARCH_WORKFLOW", "取消当前处理", true,
                    null, null,
                    "/projects/" + context.project().projectKey() + "/overview"));
        }
        return List.copyOf(actions);
    }

    private List<BlockedReason> blockedReasons(
            AgentWorkflowRepository.TaskData task) {
        if (task == null) return List.of();
        if ("FAILED".equals(task.status())) {
            return List.of(new BlockedReason(
                    "WORKFLOW_FAILED", "当前阶段处理失败，可在确认输入后重试。"));
        }
        if ("REVISION_REQUIRED".equals(task.status())) {
            return List.of(new BlockedReason(
                    "REVISION_REQUIRED", "内部审核要求修订当前科研草案。"));
        }
        if ("CANCELLED".equals(task.status())) {
            return List.of(new BlockedReason(
                    "WORKFLOW_CANCELLED", "当前处理已取消，可重新提交研究构想。"));
        }
        return List.of();
    }

    private TodoItem todo(
            WorkspaceContext context,
            AgentWorkflowRepository.TaskData task,
            AllowedAction action) {
        if (action == null
                || "WAIT_FOR_PROCESSING".equals(action.code())
                || "VIEW_PROJECT".equals(action.code())
                || "VIEW_RESEARCH_DRAFT".equals(action.code())) {
            return null;
        }
        String occurrence = task == null
                ? "initial" : task.createdAt().toString();
        String key = WorkspaceOpaqueKey.of(
                "todo_", context.project().projectKey(), action.code(), occurrence);
        return new TodoItem(
                key,
                context.project().projectKey(),
                new LabeledCode(action.code(), action.label()),
                action.label() + "：" + context.project().name(),
                todoDescription(action.code()),
                "CONTINUE_IN_LEGACY_WORKSPACE".equals(action.code())
                        ? "PROJECT_MEMBER" : "PROJECT_EDITOR",
                action.targetRoute(),
                null,
                "OPEN");
    }

    private String todoDescription(String actionCode) {
        return switch (actionCode) {
            case "START_RESEARCH_IDEA" -> "提交结构化研究构想后，系统将生成澄清问题。";
            case "SUBMIT_CLARIFICATIONS" -> "逐项补充当前研究问题所需的信息。";
            case "CONFIRM_RESEARCH_DIRECTION" -> "复核候选方向并确认一个方向后继续。";
            case "CONFIRM_SEARCH_STRATEGY" -> "复核并确认 PubMed 检索式后才执行外部检索。";
            case "CONFIRM_OBSERVATIONAL_DESIGN" -> "确认观察性研究设计、主要结局和草案生成授权。";
            case "REQUEST_DESIGN_MODEL_ADVICE" ->
                    "在版本化规则边界内获取只读模型建议；冲突会记录且不会改变规则结果。";
            case "UPDATE_PROTOCOL_SECTION" -> "根据审核意见修订方案章节并保留不可变版本历史。";
            case "REGENERATE_PROTOCOL_SECTION" -> "恢复该章节的确定性初稿并形成新版本。";
            case "GENERATE_PROTOCOL_SECTION_CANDIDATE" -> "每次只生成一个章节候选，不会自动覆盖当前方案。";
            case "REVIEW_PROTOCOL_SECTION_CANDIDATE" -> "使用不同模型形成辅助复核建议，不替代人工审核。";
            case "APPLY_PROTOCOL_SECTION_CANDIDATE" -> "明确采纳已校验和复核的候选，追加新的章节版本。";
            case "SUBMIT_PROTOCOL_REVISION" -> "提交已修订方案，重新生成下游检查并进入新审核轮次。";
            case "ADD_INTERNAL_REVIEW_COMMENT" -> "为当前方案章节或质量检查项添加可定位批注。";
            case "CONFIRM_INTERNAL_REVIEW" -> "医学和统计审核通过后，由课题负责人完成确认。";
            case "EXPORT_RESEARCH_DRAFT" -> "选择本院已发布模板和引用格式，导出科研草案。";
            case "RETRY_RESEARCH_WORKFLOW" -> "确认输入无误后重试失败阶段。";
            default -> "当前阶段尚未迁移到 V2，请通过固定旧版入口继续。";
        };
    }

    private DirectionCandidateSet directionCandidates(
            AgentWorkflowRepository.TaskData task) {
        JsonNode candidateNode = null;
        JsonNode output = readTree(task.outputJson());
        if (output != null && output.path("directions").isArray()) {
            candidateNode = output;
        } else {
            Optional<AgentWorkflowRepository.StepData> step =
                    workflows.findLatestStep(
                            task.hospitalId(), task.id(),
                            "STEP_05_CONFIRM_DIRECTION");
            if (step.isPresent()) candidateNode = readTree(step.get().outputJson());
        }
        if (candidateNode == null
                || !candidateNode.path("directions").isArray()) {
            return null;
        }
        String hash = candidateNode.path("candidateSetHash").asText();
        if (hash.isBlank()) return null;
        JsonNode input = readTree(task.inputJson());
        String selectedId = input.path("directionId").asText();
        List<DirectionCandidate> candidates = new ArrayList<>();
        for (JsonNode direction : candidateNode.path("directions")) {
            String rawId = direction.path("id").asText();
            candidates.add(new DirectionCandidate(
                    WorkspaceOpaqueKey.of("dir_", hash, rawId),
                    direction.path("title").asText(),
                    studyType(direction.path("recommendedStudyType").asText()),
                    strings(direction.path("limitations")),
                    rawId.equals(selectedId)));
        }
        return new DirectionCandidateSet(
                WorkspaceOpaqueKey.of("set_", hash),
                candidateNode.path("candidateSetSchemaVersion")
                        .asText("direction-candidates/v1"),
                List.copyOf(candidates));
    }

    private LabeledCode studyType(String value) {
        return switch (value) {
            case "CROSS_SECTIONAL" -> new LabeledCode(value, "横断面研究");
            case "COHORT" -> new LabeledCode(value, "队列研究");
            case "CASE_CONTROL" -> new LabeledCode(value, "病例对照研究");
            default -> new LabeledCode("UNSPECIFIED", "待确认");
        };
    }

    private LabeledCode workflowStatus(
            AgentWorkflowRepository.TaskData task) {
        if ("FAILED".equals(task.status())) {
            return new LabeledCode("FAILED", "处理失败");
        }
        if ("COMPLETED".equals(task.status())) {
            return new LabeledCode("COMPLETED", "已完成");
        }
        if ("WAITING_CONFIRMATION".equals(task.status())) {
            return new LabeledCode("WAITING_USER", "等待人工确认");
        }
        if ("CANCELLED".equals(task.status())) {
            return new LabeledCode("CANCELLED", "已取消");
        }
        return new LabeledCode("PROCESSING", "处理中");
    }

    private String ideaStatus(AgentWorkflowRepository.TaskData task) {
        int stage = WorkspaceStageCatalog.indexForStep(task.currentStep());
        return stage > 0 ? "已确认输入" : "正在完善";
    }

    private AllowedAction action(
            String code, String label, boolean enabled,
            String reasonCode, String reason, String targetRoute) {
        return new AllowedAction(
                code, label, enabled, reasonCode, reason, targetRoute);
    }

    private ResponseMeta meta(WorkspaceRepository.Cursor cursor) {
        return new ResponseMeta(
                cursor.readModelVersion(), clock.instant(),
                cursor.latestEventId());
    }

    private ResponseMeta aggregateMeta(
            AuthenticatedUser actor, List<WorkspaceSummary> summaries) {
        long version = 0;
        long latestEvent = 0;
        for (WorkspaceSummary summary : summaries) {
            Optional<ProjectRepository.ProjectData> project =
                    projects.findVisibleByKey(
                            actor.hospitalId(), summary.projectKey(), actor.userId(),
                            actor.hasRole(Role.HOSPITAL_ADMIN));
            if (project.isEmpty()) continue;
            WorkspaceRepository.Cursor cursor = workspace.requireCursor(
                    actor.hospitalId(), project.get().id(), clock.instant());
            version = Math.max(version, cursor.readModelVersion());
            latestEvent = Math.max(latestEvent, cursor.latestEventId());
        }
        return new ResponseMeta(version, clock.instant(), latestEvent);
    }

    private AuthenticatedUser requireActor() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (actor.forcePasswordChange()) {
            throw BusinessException.forbidden("首次登录必须先修改密码");
        }
        if (actor.hospitalId() == null) {
            throw BusinessException.forbidden("平台管理员不能访问医院课题工作台");
        }
        return actor;
    }

    private int pageSize(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        return value;
    }

    private JsonNode readTree(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("课题工作台事实源数据损坏", exception);
        }
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        });
        return List.copyOf(values);
    }

    private Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return Map.copyOf(values);
    }

    record WorkspaceContext(
            AuthenticatedUser actor,
            ProjectRepository.ProjectData project,
            ProjectMemberRole memberRole,
            boolean canEdit) {}

    record Aggregate(
            WorkspaceContext context,
            AgentWorkflowRepository.TaskData task,
            WorkspaceRepository.Cursor cursor,
            WorkspaceSummary summary,
            List<StageView> stages,
            TodoItem todo,
            boolean todoAssignedToActor) {}

    record DirectionSelection(
            AgentWorkflowRepository.TaskData task,
            String rawDirectionId,
            UUID candidateSetId,
            String candidateSetHash) {}
}
