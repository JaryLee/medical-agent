package com.jarylee.medicalagent.auth;

import com.jarylee.medicalagent.audit.AuditService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthenticationService {
    private static final int MAX_FAILURES = 5;
    private final IdentityRepository repository;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final AuditService auditService;

    public AuthenticationService(IdentityRepository repository, PasswordEncoder encoder, Clock clock,
                                 AuditService auditService) {
        this.repository = repository;
        this.encoder = encoder;
        this.clock = clock;
        this.auditService = auditService;
    }

    public LoginResult login(String hospitalCode, String username, String password) {
        Instant now = clock.instant();
        UUID hospitalId = hospitalCode == null || hospitalCode.isBlank() ? null :
                repository.findHospitalByCode(hospitalCode)
                        .map(IdentityRepository.HospitalData::id)
                        .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        IdentityRepository.UserData user = repository.findUser(hospitalId, username)
                .orElseThrow(() -> {
                    auditService.recordSystem(hospitalId, "LOGIN_FAILED", "USER", username);
                    return new BadCredentialsException("用户名或密码错误");
                });
        if (!user.enabled()) {
            auditService.recordSystem(hospitalId, "LOGIN_DISABLED", "USER", username);
            throw new BadCredentialsException("账号已禁用");
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            auditService.recordSystem(hospitalId, "LOGIN_LOCKED", "USER", username);
            throw new BadCredentialsException("账号暂时锁定");
        }
        if (!encoder.matches(password, user.passwordHash())) {
            int failedAttempts = user.failedAttempts() + 1;
            Instant lockedUntil = user.lockedUntil();
            if (failedAttempts >= MAX_FAILURES) {
                lockedUntil = now.plus(Duration.ofMinutes(15));
                failedAttempts = 0;
            }
            repository.updateUserState(copy(user, user.passwordHash(), user.enabled(),
                    user.forcePasswordChange(), failedAttempts, lockedUntil));
            auditService.recordSystem(hospitalId, "LOGIN_FAILED", "USER", username);
            throw new BadCredentialsException("用户名或密码错误");
        }
        user = copy(user, user.passwordHash(), user.enabled(), user.forcePasswordChange(), 0, null);
        repository.updateUserState(user);
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        repository.insertSession(hash(token), user.id(), now.plus(Duration.ofHours(8)));
        AuthenticatedUser principal = principal(user);
        auditService.record(principal, "LOGIN_SUCCESS", "USER", user.id().toString());
        return new LoginResult(token, principal);
    }

    public AuthenticatedUser authenticateToken(String token) {
        if (token == null || token.isBlank()) return null;
        var session = repository.findSession(hash(token)).orElse(null);
        if (session == null || session.expiresAt().isBefore(clock.instant())) return null;
        var user = repository.findUserById(session.userId()).orElse(null);
        return user == null || !user.enabled() ? null : principal(user);
    }

    public void logout(String token) {
        if (token != null) repository.revokeSession(hash(token));
    }

    public void changePassword(AuthenticatedUser principal, String currentPassword, String newPassword) {
        IdentityRepository.UserData user = repository.findUserById(principal.userId())
                .orElseThrow(() -> new BadCredentialsException("账号不存在"));
        if (!encoder.matches(currentPassword, user.passwordHash())) {
            throw new BadCredentialsException("当前密码错误");
        }
        validatePassword(newPassword);
        user = copy(user, encoder.encode(newPassword), user.enabled(), false,
                user.failedAttempts(), user.lockedUntil());
        repository.updateUserState(user);
        repository.revokeSessionsByUser(user.id());
        auditService.record(principal, "PASSWORD_CHANGED", "USER", user.id().toString());
    }

    public void validatePassword(String password) {
        if (password == null || password.length() < 12
                || !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*")
                || !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("密码至少12位，且包含大小写字母和数字");
        }
    }

    private AuthenticatedUser principal(IdentityRepository.UserData user) {
        return new AuthenticatedUser(user.id(), user.hospitalId(), user.username(),
                user.roles(), user.forcePasswordChange());
    }

    private IdentityRepository.UserData copy(IdentityRepository.UserData user, String passwordHash,
                                              boolean enabled, boolean forcePasswordChange,
                                              int failedAttempts, Instant lockedUntil) {
        return new IdentityRepository.UserData(user.id(), user.hospitalId(), user.username(),
                passwordHash, user.roles(), enabled, forcePasswordChange, failedAttempts, lockedUntil);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record LoginResult(String token, AuthenticatedUser user) {}
}
