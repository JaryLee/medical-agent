package com.jarylee.medicalagent.auth;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.MemoryAuditRepository;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AuthenticationServiceTest {
    private final PlatformStore store = new PlatformStore();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-26T04:00:00Z"), ZoneOffset.UTC);
    private final AuthenticationService service =
            new AuthenticationService(new MemoryIdentityRepository(store), encoder, clock,
                    new AuditService(new MemoryAuditRepository(store)));
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        store.hospitals.put(hospitalId,
                new PlatformStore.HospitalRow(hospitalId, "HOSP-A", "医院A", clock.instant()));
        store.users.put(userId, new PlatformStore.UserRow(userId, hospitalId, "doctor",
                encoder.encode("InitialPass123"), Set.of(Role.DOCTOR)));
    }

    @Test
    void loginUsesHospitalCodeAndOpaqueSession() {
        var login = service.login(" hosp-a ", " ｄｏｃｔｏｒ ", "InitialPass123");
        assertThat(login.token()).doesNotContain("doctor");
        assertThat(service.authenticateToken(login.token()).hospitalId()).isEqualTo(hospitalId);
        assertThat(store.sessions.keySet()).noneMatch(key -> key.equals(login.token()));
    }

    @Test
    void locksAfterFiveFailuresAndAuditsFailures() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login("HOSP-A", "doctor", "wrong"))
                    .isInstanceOf(BadCredentialsException.class);
        }
        assertThat(store.users.get(userId).lockedUntil).isAfter(clock.instant());
        assertThatThrownBy(() -> service.login("HOSP-A", "doctor", "InitialPass123"))
                .isInstanceOf(BadCredentialsException.class).hasMessageContaining("锁定");
        assertThat(store.audits).hasSize(6);
    }

    @Test
    void passwordChangeRevokesSessionsAndClearsFirstLoginFlag() {
        var login = service.login("HOSP-A", "doctor", "InitialPass123");
        service.changePassword(login.user(), "InitialPass123", "ChangedPass456");
        assertThat(service.authenticateToken(login.token())).isNull();
        assertThat(store.users.get(userId).forcePasswordChange).isFalse();
        assertThat(service.login("HOSP-A", "doctor", "ChangedPass456").user().forcePasswordChange()).isFalse();
    }
}
