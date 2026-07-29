package com.jarylee.medicalagent.document;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.MemoryAuditRepository;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CitationStyleServiceTest {
    private final MutableCurrentUser currentUser = new MutableCurrentUser();
    private final MemoryCitationStyleRepository repository =
            new MemoryCitationStyleRepository();
    private final CitationStyleService service = new CitationStyleService(
            repository,
            currentUser,
            new AuditService(new MemoryAuditRepository(new PlatformStore())),
            Clock.fixed(
                    Instant.parse("2026-07-28T13:00:00Z"),
                    ZoneOffset.UTC));

    @Test
    void versionsPublishesAndFormatsControlledHospitalStyle() {
        UUID hospitalId = UUID.randomUUID();
        currentUser.user = new AuthenticatedUser(
                UUID.randomUUID(), hospitalId, "admin",
                Set.of(Role.HOSPITAL_ADMIN), false);

        var first = service.create(
                "hospital_gbt", "医院数字格式", "GB_T_7714",
                2, "等", true, true, "摘要级证据");
        var second = service.create(
                "hospital_gbt", "医院数字格式修订", "GB_T_7714",
                3, "et al.", false, false, "摘要级证据");
        assertThat(first.versionNo()).isEqualTo(1);
        assertThat(second.versionNo()).isEqualTo(2);

        var published = service.publish(second.id(), second.version());
        var data = service.requirePublished(hospitalId, published.id());
        String reference = service.format(
                data,
                1,
                new CitationStyleService.CitationInput(
                        List.of("Zhang A", "Li B", "Wang C", "Chen D"),
                        "Verified study",
                        "Medical Journal",
                        "2026",
                        "123",
                        "10.1000/demo"));

        assertThat(reference)
                .isEqualTo("[1] Zhang A, Li B, Wang C, et al. "
                        + "Verified study[J]. Medical Journal, 2026. PMID:123.");
        assertThat(service.versionLabel(data))
                .isEqualTo("HOSPITAL_GBT/v2");
        assertThatThrownBy(() -> service.requirePublished(
                UUID.randomUUID(), published.id()))
                .hasMessageContaining("引用格式不存在");
    }

    @Test
    void rejectsCitationStyleManagementWithoutHospitalAdminRole() {
        currentUser.user = new AuthenticatedUser(
                UUID.randomUUID(), UUID.randomUUID(), "doctor",
                Set.of(Role.DOCTOR), false);

        assertThatThrownBy(service::installDefault)
                .hasMessageContaining("只有医院管理员");
    }

    private static final class MutableCurrentUser implements CurrentUserProvider {
        private AuthenticatedUser user;

        @Override
        public AuthenticatedUser requireUser() {
            return user;
        }
    }
}
