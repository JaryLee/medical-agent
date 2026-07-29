package com.jarylee.medicalagent.research;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.MemoryAuditRepository;
import com.jarylee.medicalagent.auth.*;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ResearchProjectIsolationTest {
    private final PlatformStore store = new PlatformStore();
    private final MutableCurrentUser currentUser = new MutableCurrentUser();
    private final ResearchProjectService service =
            new ResearchProjectService(new MemoryProjectRepository(store),
                    new MemoryProjectMemberRepository(store), new MemoryIdentityRepository(store), currentUser,
                    new AuditService(new MemoryAuditRepository(store)));
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final AuthenticatedUser userA =
            new AuthenticatedUser(UUID.randomUUID(), hospitalA, "doctor-a", Set.of(Role.DOCTOR), false);
    private final AuthenticatedUser userB =
            new AuthenticatedUser(UUID.randomUUID(), hospitalB, "doctor-b", Set.of(Role.DOCTOR), false);

    @BeforeEach
    void setUserA() { currentUser.user = userA; }

    @Test
    void hospitalCannotReadOrUpdateAnotherHospitalsProject() {
        var project = service.create("P-001", "医院A课题", "idem-a");
        currentUser.user = userB;

        assertThat(service.list()).isEmpty();
        assertThatThrownBy(() -> service.get(project.id()))
                .isInstanceOf(BusinessException.class).hasMessage("课题不存在");
        assertThatThrownBy(() -> service.update(project.id(), "越权修改", 0))
                .isInstanceOf(BusinessException.class).hasMessage("课题不存在");
    }

    @Test
    void createIsIdempotentAndUpdateUsesOptimisticLock() {
        var first = service.create("P-001", "初始课题", "same-key");
        var replay = service.create("DIFFERENT", "不会创建", "same-key");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(store.projects).hasSize(1);

        var updated = service.update(first.id(), "新名称", 0);
        assertThat(updated.version()).isEqualTo(1);
        assertThatThrownBy(() -> service.update(first.id(), "过期修改", 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("其他用户修改");
    }

    @Test
    void firstLoginPasswordChangeBlocksProjectAccess() {
        currentUser.user = new AuthenticatedUser(userA.userId(), hospitalA, userA.username(),
                userA.roles(), true);
        assertThatThrownBy(service::list)
                .isInstanceOf(BusinessException.class).hasMessageContaining("修改密码");
    }

    @Test
    void sameHospitalUserNeedsMembershipAndViewerCannotEdit() {
        var project = service.create("P-002", "成员权限课题", "member-project");
        var viewer = new AuthenticatedUser(
                UUID.randomUUID(), hospitalA, "viewer-a", Set.of(Role.DOCTOR), false);
        store.users.put(viewer.userId(), new PlatformStore.UserRow(
                viewer.userId(), hospitalA, viewer.username(), "not-used", viewer.roles()));

        currentUser.user = viewer;
        assertThat(service.list()).isEmpty();
        assertThatThrownBy(() -> service.get(project.id()))
                .isInstanceOf(BusinessException.class).hasMessage("课题不存在");

        currentUser.user = userA;
        service.addMember(project.id(), viewer.userId(), ProjectMemberRole.VIEWER);
        currentUser.user = viewer;
        assertThat(service.get(project.id()).id()).isEqualTo(project.id());
        assertThatThrownBy(() -> service.update(project.id(), "越权编辑", 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅可查看");
        assertThatThrownBy(() -> service.requireEditable(project.id()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅可查看");
    }

    private static class MutableCurrentUser implements CurrentUserProvider {
        private AuthenticatedUser user;
        @Override public AuthenticatedUser requireUser() { return user; }
    }
}
