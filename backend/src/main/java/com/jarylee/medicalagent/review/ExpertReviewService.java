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
import com.jarylee.medicalagent.review.ExpertReviewModels.Responsibility;
import com.jarylee.medicalagent.workflow.AgentEventStream;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        return open(task, protocolId, strobeCheckTaskId, task.outputJson());
    }

    @Transactional
    public ReviewView open(
            AgentWorkflowRepository.TaskData task,
            UUID protocolId,
            UUID strobeCheckTaskId,
            String reviewContentJson) {
        var existing = reviews.findByAgentTask(task.hospitalId(), task.id());
        String contentSha256 = ReviewContentHash.sha256(json, reviewContentJson);
        Instant now = clock.instant();
        if (existing.isPresent()) {
            var current = existing.get();
            if (current.contentSha256().equals(contentSha256)
                    && !"SUPERSEDED".equals(current.status())) {
                return view(current);
            }
            var reset = reviews.resetForNewRound(
                            task.hospitalId(), current.id(), contentSha256,
                            now, current.version())
                    .orElseThrow(() -> BusinessException.conflict(
                            "审核轮次已变化，请刷新后重试"));
            reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                    UUID.randomUUID(), task.hospitalId(), reset.id(),
                    "REVIEW_SUPERSEDED", reset.roundNo(),
                    task.createdBy(), "内容已变化，旧审核结论失效并创建新轮次。", now));
            return view(reset);
        }
        int nextRound = reviews.findLatestByProject(
                        task.hospitalId(), task.projectId())
                .map(value -> value.roundNo() + 1)
                .orElse(1);
        var created = reviews.create(new ExpertReviewRepository.ReviewTaskData(
                UUID.randomUUID(), task.hospitalId(), task.projectId(), task.id(),
                protocolId, strobeCheckTaskId, "WAITING_EXPERT_REVIEW",
                nextRound, contentSha256, false, task.createdBy(), now,
                null, null, null, null,
                null, null, null, null,
                null, null, false, 0));
        reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                UUID.randomUUID(), task.hospitalId(), created.id(),
                "REVIEW_OPENED", created.roundNo(), task.createdBy(),
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
            Responsibility responsibility,
            String content) {
        AuthenticatedUser actor = requireExpert();
        var task = requireTask(actor, agentTaskId);
        projects.get(task.projectId());
        var review = requireReview(actor.hospitalId(), agentTaskId);
        if (!"WAITING_EXPERT_REVIEW".equals(review.status())) {
            throw BusinessException.conflict("当前审核任务不再接受新批注");
        }
        requireResponsibleReviewer(review, responsibility, actor);
        validateTarget(task, protocolSectionId, protocolSectionVersionNo, strobeItemResultId);
        Instant now = clock.instant();
        var comment = reviews.addComment(new ExpertReviewRepository.ReviewCommentData(
                UUID.randomUUID(), actor.hospitalId(), review.id(),
                protocolSectionId, protocolSectionVersionNo, strobeItemResultId,
                commentType.name(), responsibility.name(), review.roundNo(),
                content.strip(), actor.userId(), now));
        reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                UUID.randomUUID(), actor.hospitalId(), review.id(), "COMMENT_ADDED",
                review.roundNo(), actor.userId(),
                responsibility.name() + " " + comment.commentType() + " 批注", now));
        audit.record(actor, "EXPERT_REVIEW_COMMENT_ADDED",
                "RESEARCH_REVIEW_TASK", review.id().toString());
        publish(task, "EXPERT_REVIEW_COMMENT_ADDED", write(comment));
        return view(review);
    }

    @Transactional
    public ReviewView decide(
            UUID agentTaskId,
            Responsibility responsibility,
            Decision decision,
            String summary,
            long expectedVersion) {
        AuthenticatedUser actor = requireExpert();
        var task = requireTask(actor, agentTaskId);
        projects.get(task.projectId());
        var review = requireReview(actor.hospitalId(), agentTaskId);
        requireResponsibleReviewer(review, responsibility, actor);
        String contentSha256 = ReviewContentHash.sha256(json, task.outputJson());
        if (!review.contentSha256().equals(contentSha256)) {
            throw BusinessException.conflict("审核内容已变化，请创建新审核轮次");
        }
        if (decision == Decision.RETURN_FOR_REVISION
                && reviews.findComments(actor.hospitalId(), review.id()).stream()
                .noneMatch(comment -> comment.reviewRoundNo() == review.roundNo()
                        && responsibility.name().equals(comment.responsibility())
                        && actor.userId().equals(comment.createdBy()))) {
            throw new IllegalArgumentException("退回修改前至少需要一条可定位批注");
        }
        Instant now = clock.instant();
        var updated = reviews.decide(
                        actor.hospitalId(), review.id(), responsibility.name(),
                        actor.userId(),
                        decision.name(), summary.strip(), contentSha256,
                        now, expectedVersion)
                .orElseThrow(() -> BusinessException.conflict(
                        "审核任务状态或版本已变化，请刷新后重试"));
        reviews.addDecision(new ExpertReviewRepository.ReviewDecisionData(
                UUID.randomUUID(), actor.hospitalId(), review.id(),
                review.roundNo(), responsibility.name(), actor.userId(),
                decision.name(), summary.strip(), contentSha256, now));
        String action = responsibility.name().replace("_REVIEW", "")
                + (decision == Decision.APPROVE
                    ? "_REVIEW_APPROVED" : "_REVIEW_RETURNED");
        reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                UUID.randomUUID(), actor.hospitalId(), review.id(), action,
                review.roundNo(), actor.userId(), summary.strip(), now));
        if (decision == Decision.RETURN_FOR_REVISION
                && !workflows.markExpertReviewReturned(actor.hospitalId(), agentTaskId)) {
            throw BusinessException.conflict("Agent 任务当前不能退回修改");
        }
        audit.record(actor,
                decision == Decision.APPROVE
                        ? responsibility.name() + "_APPROVED"
                        : responsibility.name() + "_RETURNED",
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
        String contentSha256 = ReviewContentHash.sha256(json, task.outputJson());
        Instant now = clock.instant();
        var approved = reviews.ownerConfirmAndLock(
                        actor.hospitalId(), review.id(), actor.userId(),
                        contentSha256, now, expectedVersion)
                .orElseThrow(() -> BusinessException.conflict(
                        "只有专家审核通过且版本未变化时才能由课题负责人确认"));
        if (!workflows.advanceToExport(
                actor.hospitalId(), agentTaskId, actor.userId(), now)) {
            throw BusinessException.conflict("Agent 任务当前不能进入导出步骤");
        }
        reviews.addAction(new ExpertReviewRepository.ReviewActionData(
                UUID.randomUUID(), actor.hospitalId(), review.id(),
                "OWNER_CONFIRMED", review.roundNo(), actor.userId(),
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

    private void requireResponsibleReviewer(
            ExpertReviewRepository.ReviewTaskData review,
            Responsibility responsibility,
            AuthenticatedUser actor) {
        if (actor.userId().equals(review.submittedBy())) {
            throw BusinessException.forbidden(
                    "课题负责人不能兼任医学或统计审核");
        }
        UUID assigned = responsibility == Responsibility.MEDICAL_REVIEW
                ? review.expertReviewerId()
                : review.statisticalReviewerId();
        UUID other = responsibility == Responsibility.MEDICAL_REVIEW
                ? review.statisticalReviewerId()
                : review.expertReviewerId();
        if (assigned != null && !assigned.equals(actor.userId())) {
            throw BusinessException.notFound("专家审核任务不存在");
        }
        if (actor.userId().equals(other)) {
            throw BusinessException.forbidden(
                    "医学审核和统计审核必须由不同账号完成");
        }
        boolean commentedForOtherResponsibility = reviews
                .findComments(actor.hospitalId(), review.id()).stream()
                .anyMatch(comment -> comment.reviewRoundNo() == review.roundNo()
                        && actor.userId().equals(comment.createdBy())
                        && !responsibility.name().equals(comment.responsibility()));
        if (commentedForOtherResponsibility) {
            throw BusinessException.forbidden(
                    "同一审核轮次的医学审核和统计审核必须由不同账号完成");
        }
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
                                value.responsibility(),
                                value.reviewRoundNo(),
                                value.content(), value.createdBy(), value.createdAt()))
                        .toList();
        List<ReviewAction> history =
                reviews.findActions(task.hospitalId(), task.id()).stream()
                        .map(value -> new ReviewAction(
                                value.id(), value.actionType(),
                                value.reviewRoundNo(), value.actorUserId(),
                                value.summary(), value.occurredAt()))
                        .toList();
        return new ReviewView(
                task.id(), task.projectId(), task.agentTaskId(), task.protocolId(),
                task.strobeCheckTaskId(), task.status(), task.roundNo(),
                task.submittedBy(),
                task.submittedAt(), task.expertReviewerId(),
                task.expertDecision() == null ? null
                        : Decision.valueOf(task.expertDecision()),
                task.expertSummary(), task.expertDecidedAt(),
                task.statisticalReviewerId(),
                task.statisticalDecision() == null ? null
                        : Decision.valueOf(task.statisticalDecision()),
                task.statisticalSummary(), task.statisticalDecidedAt(),
                task.ownerConfirmedBy(), task.ownerConfirmedAt(),
                task.sectionsLocked(), task.version(), comments, history);
    }

    private void publish(
            AgentWorkflowRepository.TaskData task, String eventType, String payloadJson) {
        var event = workflows.appendEvent(
                task.hospitalId(), task.id(), eventType,
                "STEP_17_WAIT_EXPERT_REVIEW", payloadJson, clock.instant());
        publishAfterCommit(event);
    }

    private void publishAfterCommit(
            AgentWorkflowRepository.EventData event) {
        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            events.publish(event);
                        }
                    });
        } else {
            events.publish(event);
        }
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
