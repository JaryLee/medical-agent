package com.jarylee.medicalagent.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.MemoryAuditRepository;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.MemoryIdentityRepository;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import com.jarylee.medicalagent.research.MemoryProjectMemberRepository;
import com.jarylee.medicalagent.research.MemoryProjectRepository;
import com.jarylee.medicalagent.research.ProjectMemberRole;
import com.jarylee.medicalagent.research.ResearchProjectService;
import com.jarylee.medicalagent.review.ExpertReviewModels.CommentType;
import com.jarylee.medicalagent.review.ExpertReviewModels.Decision;
import com.jarylee.medicalagent.review.ExpertReviewModels.Responsibility;
import com.jarylee.medicalagent.workflow.AgentEventStream;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.MemoryAgentWorkflowRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpertReviewServiceTest {
    private final PlatformStore store = new PlatformStore();
    private final MutableCurrentUser currentUser = new MutableCurrentUser();
    private final MemoryProjectMemberRepository members =
            new MemoryProjectMemberRepository(store);
    private final AuditService audit = new AuditService(new MemoryAuditRepository(store));
    private final ResearchProjectService projects = new ResearchProjectService(
            new MemoryProjectRepository(store), members,
            new MemoryIdentityRepository(store), currentUser, audit);
    private final MemoryAgentWorkflowRepository workflows =
            new MemoryAgentWorkflowRepository();
    private final MemoryExpertReviewRepository reviews =
            new MemoryExpertReviewRepository();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC);
    private final ExpertReviewService service = new ExpertReviewService(
            reviews, workflows, projects, currentUser, audit,
            new AgentEventStream(), json, clock);

    @Test
    void threeDistinctAccountsMustApproveInOrderIndependentOfResponsibilityOrder()
            throws Exception {
        Fixture fixture = fixture("REVIEW-TRIAD");
        var opened = service.open(fixture.task(), fixture.protocolId(), fixture.strobeTaskId());

        currentUser.user = expert(fixture.medicalReviewerId(), fixture.hospitalId(), "medical");
        var medicallyApproved = service.decide(
                fixture.task().id(), Responsibility.MEDICAL_REVIEW, Decision.APPROVE,
                "医学设计与终点定义可接受。", opened.version());
        assertThat(medicallyApproved.status()).isEqualTo("WAITING_EXPERT_REVIEW");
        assertThat(medicallyApproved.expertReviewerId())
                .isEqualTo(fixture.medicalReviewerId());

        assertThatThrownBy(() -> service.decide(
                fixture.task().id(), Responsibility.STATISTICAL_REVIEW,
                Decision.APPROVE, "试图兼任统计审核。", medicallyApproved.version()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须由不同账号");

        currentUser.user = owner(fixture);
        assertThatThrownBy(() -> service.ownerConfirm(
                fixture.task().id(), medicallyApproved.version()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("专家审核通过");

        currentUser.user = expert(
                fixture.statisticalReviewerId(), fixture.hospitalId(), "statistician");
        var expertsApproved = service.decide(
                fixture.task().id(), Responsibility.STATISTICAL_REVIEW,
                Decision.APPROVE, "统计方法、缺失数据和敏感性分析可接受。",
                medicallyApproved.version());
        assertThat(expertsApproved.status()).isEqualTo("EXPERT_APPROVED");
        assertThat(expertsApproved.statisticalReviewerId())
                .isEqualTo(fixture.statisticalReviewerId());

        currentUser.user = owner(fixture);
        var approved = service.ownerConfirm(fixture.task().id(), expertsApproved.version());
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.sectionsLocked()).isTrue();
        assertThat(Set.of(
                approved.ownerConfirmedBy(),
                approved.expertReviewerId(),
                approved.statisticalReviewerId())).hasSize(3);
        assertThat(workflows.findById(
                fixture.hospitalId(), fixture.task().id()).orElseThrow().currentStep())
                .isEqualTo("STEP_18_EXPORT_DOCUMENT");
    }

    @Test
    void returnRequiresCurrentReviewersOwnAnchoredComment() throws Exception {
        Fixture fixture = fixture("REVIEW-RETURN");
        var opened = service.open(fixture.task(), fixture.protocolId(), fixture.strobeTaskId());
        currentUser.user = expert(
                fixture.medicalReviewerId(), fixture.hospitalId(), "medical");

        assertThatThrownBy(() -> service.decide(
                fixture.task().id(), Responsibility.MEDICAL_REVIEW,
                Decision.RETURN_FOR_REVISION, "缺少研究对象定义。", opened.version()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("定位批注");

        var commented = service.addComment(
                fixture.task().id(), fixture.sectionId(), 2, null,
                CommentType.MEDICAL, Responsibility.MEDICAL_REVIEW,
                "请补充研究对象来源与纳入时间窗。");
        var returned = service.decide(
                fixture.task().id(), Responsibility.MEDICAL_REVIEW,
                Decision.RETURN_FOR_REVISION, "关键信息需修改后重新审核。",
                commented.version());

        assertThat(returned.status()).isEqualTo("REVISION_REQUIRED");
        assertThat(returned.comments().getFirst().responsibility())
                .isEqualTo("MEDICAL_REVIEW");
        assertThat(returned.history())
                .extracting(ExpertReviewModels.ReviewAction::actionType)
                .containsExactly(
                        "REVIEW_OPENED", "COMMENT_ADDED", "MEDICAL_REVIEW_RETURNED");
        assertThat(workflows.findById(
                fixture.hospitalId(), fixture.task().id()).orElseThrow().status())
                .isEqualTo("REVISION_REQUIRED");
    }

    @Test
    void semanticContentChangeSupersedesApprovalsAndStartsNewRound() throws Exception {
        Fixture fixture = fixture("REVIEW-ROUND");
        var opened = service.open(fixture.task(), fixture.protocolId(), fixture.strobeTaskId());
        currentUser.user = expert(
                fixture.medicalReviewerId(), fixture.hospitalId(), "medical");
        var medical = service.decide(
                fixture.task().id(), Responsibility.MEDICAL_REVIEW, Decision.APPROVE,
                "医学审核通过。", opened.version());
        currentUser.user = expert(
                fixture.statisticalReviewerId(), fixture.hospitalId(), "statistician");
        var both = service.decide(
                fixture.task().id(), Responsibility.STATISTICAL_REVIEW, Decision.APPROVE,
                "统计审核通过。", medical.version());

        String changedOutput = output(
                fixture.protocolId(), fixture.sectionId(),
                fixture.strobeTaskId(), fixture.strobeItemId(), "语义内容已修改");
        workflows.waitForExpertReview(
                fixture.hospitalId(), fixture.task().id(), changedOutput);
        var changedTask = workflows.findById(
                fixture.hospitalId(), fixture.task().id()).orElseThrow();

        currentUser.user = owner(fixture);
        assertThatThrownBy(() -> service.ownerConfirm(
                fixture.task().id(), both.version()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本未变化");

        var newRound = service.open(
                changedTask, fixture.protocolId(), fixture.strobeTaskId());
        assertThat(newRound.reviewRoundNo()).isEqualTo(2);
        assertThat(newRound.status()).isEqualTo("WAITING_EXPERT_REVIEW");
        assertThat(newRound.expertDecision()).isNull();
        assertThat(newRound.statisticalDecision()).isNull();
        assertThat(newRound.history())
                .extracting(ExpertReviewModels.ReviewAction::actionType)
                .containsExactly(
                        "REVIEW_OPENED", "MEDICAL_REVIEW_APPROVED",
                        "STATISTICAL_REVIEW_APPROVED", "REVIEW_SUPERSEDED");
    }

    private Fixture fixture(String projectCode) throws Exception {
        UUID hospitalId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID medicalReviewerId = UUID.randomUUID();
        UUID statisticalReviewerId = UUID.randomUUID();
        currentUser.user = new AuthenticatedUser(
                ownerId, hospitalId, "owner", Set.of(Role.DOCTOR), false);
        var project = projects.create(projectCode, "三方审核课题", "triad-project");
        members.add(
                hospitalId, project.id(), medicalReviewerId, ProjectMemberRole.VIEWER);
        members.add(
                hospitalId, project.id(), statisticalReviewerId, ProjectMemberRole.VIEWER);

        UUID taskId = UUID.randomUUID();
        UUID protocolId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID strobeTaskId = UUID.randomUUID();
        UUID strobeItemId = UUID.randomUUID();
        Instant now = clock.instant();
        var task = workflows.create(new AgentWorkflowRepository.TaskData(
                taskId, hospitalId, project.id(), ownerId,
                "STEP_17_WAIT_EXPERT_REVIEW", "WAITING_CONFIRMATION",
                "{}", output(protocolId, sectionId, strobeTaskId, strobeItemId, "初稿"),
                null, now.plusSeconds(900), false, 0,
                null, null, now, now, null), "review-agent-task-" + projectCode);
        return new Fixture(
                hospitalId, ownerId, medicalReviewerId, statisticalReviewerId,
                protocolId, sectionId, strobeTaskId, strobeItemId, task);
    }

    private String output(
            UUID protocolId, UUID sectionId, UUID strobeTaskId,
            UUID strobeItemId, String content) throws Exception {
        return json.writeValueAsString(Map.of(
                "protocolDraft", Map.of(
                        "protocolId", protocolId,
                        "sections", List.of(Map.of(
                                "sectionId", sectionId,
                                "versionNo", 2,
                                "content", content))),
                "strobeCompletenessCheck", Map.of(
                        "checkTaskId", strobeTaskId,
                        "items", List.of(Map.of("itemResultId", strobeItemId)))));
    }

    private AuthenticatedUser owner(Fixture fixture) {
        return new AuthenticatedUser(
                fixture.ownerId(), fixture.hospitalId(), "owner",
                Set.of(Role.DOCTOR), false);
    }

    private AuthenticatedUser expert(UUID userId, UUID hospitalId, String username) {
        return new AuthenticatedUser(
                userId, hospitalId, username, Set.of(Role.EXPERT), false);
    }

    private record Fixture(
            UUID hospitalId,
            UUID ownerId,
            UUID medicalReviewerId,
            UUID statisticalReviewerId,
            UUID protocolId,
            UUID sectionId,
            UUID strobeTaskId,
            UUID strobeItemId,
            AgentWorkflowRepository.TaskData task
    ) {}

    private static final class MutableCurrentUser implements CurrentUserProvider {
        private AuthenticatedUser user;

        @Override
        public AuthenticatedUser requireUser() {
            return user;
        }
    }
}
