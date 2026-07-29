package com.jarylee.medicalagent.audit;

import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditQueryServiceTest {
    private final PlatformStore store = new PlatformStore();
    private final MemoryAuditRepository repository = new MemoryAuditRepository(store);
    private final MutableCurrentUser current = new MutableCurrentUser();
    private final AuditQueryService query = new AuditQueryService(repository, current);

    @Test
    void hospitalAdminOnlyReadsOwnHospitalAndDoctorIsRejected() {
        UUID hospitalA = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        repository.save(audit(hospitalA, "PROJECT_CREATED"));
        repository.save(audit(hospitalB, "USER_CREATED"));
        current.user = user(hospitalA, Role.HOSPITAL_ADMIN);

        assertThat(query.recent(100)).extracting(AuditQueryService.AuditView::action)
                .containsExactly("PROJECT_CREATED");

        current.user = user(hospitalA, Role.DOCTOR);
        assertThatThrownBy(() -> query.recent(100))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权");
    }

    private AuditRepository.AuditData audit(UUID hospitalId, String action) {
        return new AuditRepository.AuditData(UUID.randomUUID(), hospitalId, UUID.randomUUID(),
                action, "TEST", UUID.randomUUID().toString(), java.time.Instant.now());
    }

    private AuthenticatedUser user(UUID hospitalId, Role role) {
        return new AuthenticatedUser(UUID.randomUUID(), hospitalId, "user", Set.of(role), false);
    }

    private static class MutableCurrentUser implements CurrentUserProvider {
        private AuthenticatedUser user;
        @Override public AuthenticatedUser requireUser() { return user; }
    }
}
