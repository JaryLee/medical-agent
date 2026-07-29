package com.jarylee.medicalagent.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.MemoryAuditRepository;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.MemoryIdentityRepository;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import com.jarylee.medicalagent.research.MemoryProjectMemberRepository;
import com.jarylee.medicalagent.research.MemoryProjectRepository;
import com.jarylee.medicalagent.research.ProjectMemberRole;
import com.jarylee.medicalagent.research.ResearchProjectService;
import com.jarylee.medicalagent.review.ExpertReviewModels.CommentType;
import com.jarylee.medicalagent.review.ExpertReviewModels.Decision;
import com.jarylee.medicalagent.workflow.AgentEventStream;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.MemoryAgentWorkflowRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    void expertReturnRequiresAnchoredCommentAndMovesWorkflowToRevisionRequired() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID expertId = UUID.randomUUID();
        currentUser.user = new AuthenticatedUser(
                ownerId, hospitalId, "owner", Set.of(Role.DOCTOR), false);
        var project = projects.create("REVIEW-001", "专家审核课题", "review-project");
        members.add(hospitalId, project.id(), expertId, ProjectMemberRole.VIEWER);

        UUID agentTaskId = UUID.randomUUID();
        UUID protocolId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID strobeTaskId = UUID.randomUUID();
        UUID strobeItemId = UUID.randomUUID();
        String output = json.writeValueAsString(Map.of(
                "protocolDraft", Map.of(
                        "protocolId", protocolId,
                        "sections", java.util.List.of(Map.of(
                                "sectionId", sectionId,
                                "versionNo", 2))),
                "strobeCompletenessCheck", Map.of(
                        "checkTaskId", strobeTaskId,
                        "items", java.util.List.of(Map.of(
                                "itemResultId", strobeItemId)))));
        Instant now = clock.instant();
        var task = workflows.create(new AgentWorkflowRepository.TaskData(
                agentTaskId, hospitalId, project.id(), ownerId,
                "STEP_17_WAIT_EXPERT_REVIEW", "WAITING_CONFIRMATION",
                "{}", output, null, now.plusSeconds(900), false, 0,
                null, null, now, now, null), "review-agent-task");
        var opened = service.open(task, protocolId, strobeTaskId);

        currentUser.user = new AuthenticatedUser(
                expertId, hospitalId, "expert", Set.of(Role.EXPERT), false);
        var commented = service.addComment(
                agentTaskId, sectionId, 2, null, CommentType.MEDICAL,
                "请补充研究对象来源与纳入时间窗。");
        assertThat(commented.comments()).hasSize(1);
        assertThat(commented.comments().getFirst().protocolSectionVersionNo())
                .isEqualTo(2);

        var returned = service.decide(
                agentTaskId, Decision.RETURN_FOR_REVISION,
                "关键信息需修改后重新审核。", opened.version());
        assertThat(returned.status()).isEqualTo("REVISION_REQUIRED");
        assertThat(returned.history())
                .extracting(ExpertReviewModels.ReviewAction::actionType)
                .containsExactly(
                        "REVIEW_OPENED", "COMMENT_ADDED", "RETURNED_FOR_REVISION");
        assertThat(workflows.findById(hospitalId, agentTaskId).orElseThrow().status())
                .isEqualTo("REVISION_REQUIRED");
    }

    private static final class MutableCurrentUser implements CurrentUserProvider {
        private AuthenticatedUser user;

        @Override
        public AuthenticatedUser requireUser() {
            return user;
        }
    }
}
