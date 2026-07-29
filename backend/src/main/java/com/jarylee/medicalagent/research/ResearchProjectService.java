package com.jarylee.medicalagent.research;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.IdentityRepository;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ResearchProjectService {
    private final ProjectRepository repository;
    private final ProjectMemberRepository members;
    private final IdentityRepository identities;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public ResearchProjectService(ProjectRepository repository, ProjectMemberRepository members,
                                  IdentityRepository identities, CurrentUserProvider currentUser,
                                  AuditService audit) {
        this.repository = repository;
        this.members = members;
        this.identities = identities;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional
    public ProjectView create(String code, String name, String idempotencyKey) {
        AuthenticatedUser actor = requireReadyUser();
        if (actor.hospitalId() == null) throw BusinessException.forbidden("平台管理员不能创建医院课题");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少Idempotency-Key");
        }
        var existing = repository.findIdempotentResource(actor.hospitalId(), actor.userId(),
                "PROJECT_CREATE", idempotencyKey);
        if (existing.isPresent()) return get(existing.get());
        var row = repository.insert(actor.hospitalId(), code.trim(), name.trim());
        members.add(actor.hospitalId(), row.id(), actor.userId(), ProjectMemberRole.OWNER);
        repository.saveIdempotency(actor.hospitalId(), actor.userId(), "PROJECT_CREATE",
                idempotencyKey, row.id());
        audit.record(actor, "PROJECT_CREATED", "RESEARCH_PROJECT", row.id().toString());
        return view(row);
    }

    public List<ProjectView> list() {
        AuthenticatedUser actor = requireReadyUser();
        boolean allAccess = actor.hasRole(Role.HOSPITAL_ADMIN);
        return repository.findAll(actor.hospitalId()).stream()
                .filter(row -> allAccess || members.findRole(
                        actor.hospitalId(), row.id(), actor.userId()).isPresent())
                .map(this::view).toList();
    }

    public ProjectView get(UUID id) {
        AuthenticatedUser actor = requireReadyUser();
        var row = repository.findById(actor.hospitalId(), id)
                .orElseThrow(() -> BusinessException.notFound("课题不存在"));
        requireAccess(actor, id);
        return view(row);
    }

    public ProjectView requireEditable(UUID id) {
        AuthenticatedUser actor = requireReadyUser();
        var row = repository.findById(actor.hospitalId(), id)
                .orElseThrow(() -> BusinessException.notFound("课题不存在"));
        requireEditAccess(actor, id);
        return view(row);
    }

    public ProjectView requireOwner(UUID id) {
        AuthenticatedUser actor = requireReadyUser();
        var row = repository.findById(actor.hospitalId(), id)
                .orElseThrow(() -> BusinessException.notFound("课题不存在"));
        var role = members.findRole(actor.hospitalId(), id, actor.userId())
                .orElseThrow(() -> BusinessException.notFound("课题不存在"));
        if (role != ProjectMemberRole.OWNER) {
            throw BusinessException.forbidden("只有课题负责人可以完成最终确认");
        }
        return view(row);
    }

    @Transactional
    public ProjectView update(UUID id, String name, long expectedVersion) {
        AuthenticatedUser actor = requireReadyUser();
        requireEditAccess(actor, id);
        var row = repository.update(actor.hospitalId(), id, name.trim(), expectedVersion);
        audit.record(actor, "PROJECT_UPDATED", "RESEARCH_PROJECT", row.id().toString());
        return view(row);
    }

    @Transactional
    public MemberView addMember(UUID projectId, UUID userId, ProjectMemberRole role) {
        AuthenticatedUser actor = requireReadyUser();
        repository.findById(actor.hospitalId(), projectId)
                .orElseThrow(() -> BusinessException.notFound("课题不存在"));
        requireManageAccess(actor, projectId);
        var target = identities.findUserById(userId)
                .filter(user -> user.enabled() && Objects.equals(user.hospitalId(), actor.hospitalId()))
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        members.add(actor.hospitalId(), projectId, userId, role);
        audit.record(actor, "PROJECT_MEMBER_ADDED", "RESEARCH_PROJECT", projectId.toString());
        return new MemberView(userId, target.username(), role);
    }

    public List<MemberView> listMembers(UUID projectId) {
        AuthenticatedUser actor = requireReadyUser();
        repository.findById(actor.hospitalId(), projectId)
                .orElseThrow(() -> BusinessException.notFound("课题不存在"));
        requireAccess(actor, projectId);
        return members.findAll(actor.hospitalId(), projectId).stream()
                .map(member -> {
                    String username = identities.findUserById(member.userId())
                            .map(IdentityRepository.UserData::username).orElse("unknown");
                    return new MemberView(member.userId(), username, member.role());
                }).toList();
    }

    private void requireAccess(AuthenticatedUser actor, UUID projectId) {
        if (actor.hasRole(Role.HOSPITAL_ADMIN)) return;
        if (members.findRole(actor.hospitalId(), projectId, actor.userId()).isEmpty()) {
            throw BusinessException.notFound("课题不存在");
        }
    }

    private void requireEditAccess(AuthenticatedUser actor, UUID projectId) {
        if (actor.hasRole(Role.HOSPITAL_ADMIN)) return;
        var role = members.findRole(actor.hospitalId(), projectId, actor.userId())
                .orElseThrow(() -> BusinessException.notFound("课题不存在"));
        if (role == ProjectMemberRole.VIEWER) throw BusinessException.forbidden("课题仅可查看");
    }

    private void requireManageAccess(AuthenticatedUser actor, UUID projectId) {
        if (actor.hasRole(Role.HOSPITAL_ADMIN)) return;
        var role = members.findRole(actor.hospitalId(), projectId, actor.userId())
                .orElseThrow(() -> BusinessException.notFound("课题不存在"));
        if (role != ProjectMemberRole.OWNER) throw BusinessException.forbidden("无权管理课题成员");
    }

    private AuthenticatedUser requireReadyUser() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (actor.forcePasswordChange()) throw BusinessException.forbidden("首次登录必须先修改密码");
        return actor;
    }

    private ProjectView view(ProjectRepository.ProjectData row) {
        return new ProjectView(row.id(), row.code(), row.name(), row.version());
    }

    public record ProjectView(UUID id, String code, String name, long version) {}
    public record MemberView(UUID userId, String username, ProjectMemberRole role) {}
}
