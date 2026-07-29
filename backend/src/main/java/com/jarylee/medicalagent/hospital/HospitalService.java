package com.jarylee.medicalagent.hospital;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.*;
import com.jarylee.medicalagent.common.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class HospitalService {
    private final IdentityRepository repository;
    private final CurrentUserProvider currentUser;
    private final AuthenticationService authentication;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public HospitalService(IdentityRepository repository, CurrentUserProvider currentUser,
                           AuthenticationService authentication, PasswordEncoder encoder,
                           AuditService audit) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.authentication = authentication;
        this.encoder = encoder;
        this.audit = audit;
    }

    public HospitalView createHospital(String code, String name) {
        AuthenticatedUser actor = requireRole(Role.PLATFORM_ADMIN);
        if (repository.findHospitalByCode(code.trim()).isPresent()) {
            throw BusinessException.conflict("医院编码已存在");
        }
        var row = new IdentityRepository.HospitalData(
                UUID.randomUUID(), code.trim(), name.trim(), Instant.now());
        repository.insertHospital(row);
        audit.record(actor, "HOSPITAL_CREATED", "HOSPITAL", row.id().toString());
        return view(row);
    }

    public List<HospitalView> listHospitals() {
        requireRole(Role.PLATFORM_ADMIN);
        return repository.findHospitals().stream().map(this::view).toList();
    }

    public UserView createUser(UUID requestedHospitalId, String username, String initialPassword,
                               Set<Role> roles) {
        AuthenticatedUser actor = currentUser.requireUser();
        UUID hospitalId;
        if (actor.hasRole(Role.PLATFORM_ADMIN)) {
            hospitalId = Objects.requireNonNull(requestedHospitalId, "平台管理员必须指定目标医院");
        } else if (actor.hasRole(Role.HOSPITAL_ADMIN)) {
            hospitalId = actor.hospitalId();
        } else {
            throw BusinessException.forbidden("无权创建用户");
        }
        if (repository.findHospitalById(hospitalId).isEmpty()) throw BusinessException.notFound("医院不存在");
        if (roles.contains(Role.PLATFORM_ADMIN)) throw BusinessException.forbidden("医院用户不能授予平台管理员");
        if (repository.findUser(hospitalId, username.trim()).isPresent()) {
            throw BusinessException.conflict("本院用户名已存在");
        }
        authentication.validatePassword(initialPassword);
        var user = new IdentityRepository.UserData(UUID.randomUUID(), hospitalId, username.trim(),
                encoder.encode(initialPassword), Set.copyOf(roles), true, true, 0, null);
        repository.insertUser(user);
        audit.record(actor, "USER_CREATED", "USER", user.id().toString());
        return view(user);
    }

    public List<UserView> listUsers() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (!actor.hasRole(Role.HOSPITAL_ADMIN) && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw BusinessException.forbidden("无权查看用户");
        }
        return repository.findUsers().stream()
                .filter(row -> actor.hasRole(Role.PLATFORM_ADMIN)
                        || Objects.equals(row.hospitalId(), actor.hospitalId()))
                .map(this::view).toList();
    }

    public void disableUser(UUID userId) {
        AuthenticatedUser actor = currentUser.requireUser();
        IdentityRepository.UserData target = repository.findUserById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        if (!actor.hasRole(Role.PLATFORM_ADMIN)
                && (!actor.hasRole(Role.HOSPITAL_ADMIN)
                || !Objects.equals(actor.hospitalId(), target.hospitalId()))) {
            throw BusinessException.notFound("用户不存在");
        }
        target = new IdentityRepository.UserData(target.id(), target.hospitalId(), target.username(),
                target.passwordHash(), target.roles(), false, target.forcePasswordChange(),
                target.failedAttempts(), target.lockedUntil());
        repository.updateUserState(target);
        repository.revokeSessionsByUser(userId);
        audit.record(actor, "USER_DISABLED", "USER", userId.toString());
    }

    private AuthenticatedUser requireRole(Role role) {
        AuthenticatedUser actor = currentUser.requireUser();
        if (!actor.hasRole(role)) throw BusinessException.forbidden("权限不足");
        return actor;
    }

    private HospitalView view(IdentityRepository.HospitalData row) {
        return new HospitalView(row.id(), row.code(), row.name());
    }

    private UserView view(IdentityRepository.UserData row) {
        return new UserView(row.id(), row.hospitalId(), row.username(), row.roles(),
                row.enabled(), row.forcePasswordChange());
    }

    public record HospitalView(UUID id, String code, String name) {}
    public record UserView(UUID id, UUID hospitalId, String username, Set<Role> roles,
                           boolean enabled, boolean forcePasswordChange) {}
}
