package com.jarylee.medicalagent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.research.ResearchProjectService;
import com.jarylee.medicalagent.review.ExpertReviewModels.CommentType;
import com.jarylee.medicalagent.review.ExpertReviewModels.Decision;
import com.jarylee.medicalagent.review.ExpertReviewModels.ReviewAction;
import com.jarylee.medicalagent.review.ExpertReviewModels.ReviewComment;
import com.jarylee.medicalagent.review.ExpertReviewModels.ReviewView;
import com.jarylee.medicalagent.workflow.AgentEventStream;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ExpertReviewService {
    public static final String REVIEW_SCHEMA_VERSION = "expert-review/v1";

    private final ExpertReviewRepository reviews;
    private final AgentWorkflowRepository workflows;
    private final ResearchProjectService projects;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final AgentEventStream events;
    private final ObjectMapper json;
    private final Clock clock;

    public ExpertReviewService(
            ExpertReviewRepository reviews,
            AgentWorkflowRepository workflows,
            ResearchProjectService projects,
            CurrentUserProvider currentUser,
            AuditService audit,
            AgentEventStream events,
            ObjectMapper json,
            Clock clock) {
        this.reviews = reviews;
        this.workflows = workflows;
        this.projects = projects;
        this.currentUser = currentUser;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public ReviewView open(
            AgentWorkflowRepository.TaskData task,
            UUID protocolId,
            UUID strobeCheckTaskId) {
        var existing = reviews.findByAgentTask(task.hospitalId(), task.id());
        if (existing.isPresent()) return view(existing.get());
        Instant now = clock.instant();
        var created = reviews.create(new ExpertReviewRepository.ReviewTaskData(
                UUID.randomUUID(), task.hospitalId(), task.projectId(), task.id(),
                protocolId, strobeCheckTaskId, "WAITING_EXPERT_REVIEW",
                task.createdBy(), now, null, null, null, null,
                null, null, false, 0));
        reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                UUID.randomUUID(), task.hospitalId(), created.id(),
                "REVIEW_OPENED", task.createdBy(),
                "STEP16 完成，已提交专家审核。", now));
        return view(created);
    }

    public ReviewView get(UUID agentTaskId) {
        AuthenticatedUser actor = requireReadyUser();
        var task = requireTask(actor, agentTaskId);
        projects.get(task.projectId());
        return view(requireReview(actor.hospitalId(), agentTaskId));
    }

    @Transactional
    public ReviewView addComment(
            UUID agentTaskId,
            UUID protocolSectionId,
            Integer protocolSectionVersionNo,
            UUID strobeItemResultId,
            CommentType commentType,
            String content) {
        AuthenticatedUser actor = requireExpert();
        var task = requireTask(actor, agentTaskId);
        projects.get(task.projectId());
        var review = requireReview(actor.hospitalId(), agentTaskId);
        if (!"WAITING_EXPERT_REVIEW".equals(review.status())) {
            throw BusinessException.conflict("当前审核任务不再接受新批注");
        }
        validateTarget(task, protocolSectionId, protocolSectionVersionNo, strobeItemResultId);
        Instant now = clock.instant();
        var comment = reviews.addComment(new ExpertReviewRepository.ReviewCommentData(
                UUID.randomUUID(), actor.hospitalId(), review.id(),
                protocolSectionId, protocolSectionVersionNo, strobeItemResultId,
                commentType.name(), content.strip(), actor.userId(), now));
        reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                UUID.randomUUID(), actor.hospitalId(), review.id(), "COMMENT_ADDED",
                actor.userId(), comment.commentType() + " 批注", now));
        audit.record(actor, "EXPERT_REVIEW_COMMENT_ADDED",
                "RESEARCH_REVIEW_TASK", review.id().toString());
        publish(task, "EXPERT_REVIEW_COMMENT_ADDED", write(comment));
        return view(review);
    }

    @Transactional
    public ReviewView decide(
            UUID agentTaskId,
            Decision decision,
            String summary,
            long expectedVersion) {
        AuthenticatedUser actor = requireExpert();
        var task = requireTask(actor, agentTaskId);
        projects.get(task.projectId());
        var review = requireReview(actor.hospitalId(), agentTaskId);
        if (decision == Decision.RETURN_FOR_REVISION
                && reviews.findComments(actor.hospitalId(), review.id()).isEmpty()) {
            throw new IllegalArgumentException("退回修改前至少需要一条可定位批注");
        }
        Instant now = clock.instant();
        var updated = reviews.decide(
                        actor.hospitalId(), review.id(), actor.userId(),
                        decision.name(), summary.strip(), now, expectedVersion)
                .orElseThrow(() -> BusinessException.conflict(
                        "审核任务状态或版本已变化，请刷新后重试"));
        String action = decision == Decision.APPROVE
                ? "EXPERT_APPROVED" : "RETURNED_FOR_REVISION";
        reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                UUID.randomUUID(), actor.hospitalId(), review.id(), action,
                actor.userId(), summary.strip(), now));
        if (decision == Decision.RETURN_FOR_REVISION
                && !workflows.markExpertReviewReturned(actor.hospitalId(), agentTaskId)) {
            throw BusinessException.conflict("Agent 任务当前不能退回修改");
        }
        audit.record(actor,
                decision == Decision.APPROVE
                        ? "EXPERT_REVIEW_APPROVED" : "EXPERT_REVIEW_RETURNED",
                "RESEARCH_REVIEW_TASK", review.id().toString());
        publish(task, action, write(updated));
        return view(updated);
    }

    @Transactional
    public ReviewView ownerConfirm(UUID agentTaskId, long expectedVersion) {
        AuthenticatedUser actor = requireReadyUser();
        var task = requireTask(actor, agentTaskId);
        projects.requireOwner(task.projectId());
        var review = requireReview(actor.hospitalId(), agentTaskId);
        Instant now = clock.instant();
        var approved = reviews.ownerConfirmAndLock(
                        actor.hospitalId(), review.id(), actor.userId(),
                        now, expectedVersion)
                .orElseThrow(() -> BusinessException.conflict(
                        "只有专家审核通过且版本未变化时才能由课题负责人确认"));
        if (!workflows.advanceToExport(
                actor.hospitalId(), agentTaskId, actor.userId(), now)) {
            throw BusinessException.conflict("Agent 任务当前不能进入导出步骤");
        }
        reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                UUID.randomUUID(), actor.hospitalId(), review.id(),
                "OWNER_CONFIRMED", actor.userId(),
                "课题负责人确认专家审核结论并锁定当前章节版本。", now));
        audit.record(actor, "EXPERT_REVIEW_OWNER_CONFIRMED",
                "RESEARCH_REVIEW_TASK", review.id().toString());
        var advanced = workflows.findById(actor.hospitalId(), agentTaskId).orElse(task);
        publish(advanced, "EXPERT_REVIEW_COMPLETED", write(approved));
        publish(advanced, "EXPORT_CONFIRMATION_REQUIRED",
                write(new StatusPayload("STEP_18_EXPORT_DOCUMENT", "WAITING_CONFIRMATION")));
        return view(approved);
    }

    private void validateTarget(
            AgentWorkflowRepository.TaskData task,
            UUID protocolSectionId,
            Integer protocolSectionVersionNo,
            UUID strobeItemResultId) {
        boolean sectionTarget = protocolSectionId != null
                || protocolSectionVersionNo != null;
        boolean strobeTarget = strobeItemResultId != null;
        if (sectionTarget == strobeTarget
                || (sectionTarget
                && (protocolSectionId == null || protocolSectionVersionNo == null))) {
            throw new IllegalArgumentException(
                    "批注必须且只能锚定一个方案章节版本或一个 STROBE 条目");
        }
        JsonNode output = readTree(task.outputJson());
        if (sectionTarget) {
            boolean found = false;
            for (JsonNode section : output.path("protocolDraft").path("sections")) {
                if (protocolSectionId.toString().equals(section.path("sectionId").asText())
                        && protocolSectionVersionNo == section.path("versionNo").asInt()) {
                    found = true;
                    break;
                }
            }
            if (!found) throw new IllegalArgumentException("方案章节版本不属于当前审核快照");
        } else {
            boolean found = false;
            for (JsonNode item : output.path("strobeCompletenessCheck").path("items")) {
                if (strobeItemResultId.toString()
                        .equals(item.path("itemResultId").asText())) {
                    found = true;
                    break;
                }
            }
            if (!found) throw new IllegalArgumentException("STROBE 条目不属于当前审核快照");
        }
    }

    private AgentWorkflowRepository.TaskData requireTask(
            AuthenticatedUser actor, UUID agentTaskId) {
        return workflows.findById(actor.hospitalId(), agentTaskId)
                .orElseThrow(() -> BusinessException.notFound("Agent 任务不存在"));
    }

    private ExpertReviewRepository.ReviewTaskData requireReview(
            UUID hospitalId, UUID agentTaskId) {
        return reviews.findByAgentTask(hospitalId, agentTaskId)
                .orElseThrow(() -> BusinessException.notFound("专家审核任务不存在"));
    }

    private AuthenticatedUser requireExpert() {
        AuthenticatedUser actor = requireReadyUser();
        if (!actor.hasRole(Role.EXPERT)) {
            throw BusinessException.forbidden("只有本课题有访问权限的专家可以执行审核");
        }
        return actor;
    }

    private AuthenticatedUser requireReadyUser() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (actor.forcePasswordChange()) {
            throw BusinessException.forbidden("首次登录必须先修改密码");
        }
        if (actor.hospitalId() == null) {
            throw BusinessException.forbidden("平台管理员不能执行医院课题审核");
        }
        return actor;
    }

    private ReviewView view(ExpertReviewRepository.ReviewTaskData task) {
        List<ReviewComment> comments =
                reviews.findComments(task.hospitalId(), task.id()).stream()
                        .map(value -> new ReviewComment(
                                value.id(), value.protocolSectionId(),
                                value.protocolSectionVersionNo(),
                                value.strobeItemResultId(),
                                CommentType.valueOf(value.commentType()),
                                value.content(), value.createdBy(), value.createdAt()))
                        .toList();
        List<ReviewAction> history =
                reviews.findActions(task.hospitalId(), task.id()).stream()
                        .map(value -> new ReviewAction(
                                value.id(), value.actionType(), value.actorUserId(),
                                value.summary(), value.occurredAt()))
                        .toList();
        return new ReviewView(
                task.id(), task.projectId(), task.agentTaskId(), task.protocolId(),
                task.strobeCheckTaskId(), task.status(), task.submittedBy(),
                task.submittedAt(), task.expertReviewerId(),
                task.expertDecision() == null ? null
                        : Decision.valueOf(task.expertDecision()),
                task.expertSummary(), task.expertDecidedAt(),
                task.ownerConfirmedBy(), task.ownerConfirmedAt(),
                task.sectionsLocked(), task.version(), comments, history);
    }

    private void publish(
            AgentWorkflowRepository.TaskData task, String eventType, String payloadJson) {
        var event = workflows.appendEvent(
                task.hospitalId(), task.id(), eventType,
                "STEP_17_WAIT_EXPERT_REVIEW", payloadJson, clock.instant());
        events.publish(event);
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 任务输出损坏", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("专家审核事件序列化失败", exception);
        }
    }

    private record StatusPayload(String stepCode, String status) {}
}
