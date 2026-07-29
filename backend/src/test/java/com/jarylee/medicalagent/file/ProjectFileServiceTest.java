package com.jarylee.medicalagent.file;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.MemoryAuditRepository;
import com.jarylee.medicalagent.auth.*;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import com.jarylee.medicalagent.research.MemoryProjectRepository;
import com.jarylee.medicalagent.research.MemoryProjectMemberRepository;
import com.jarylee.medicalagent.research.ResearchProjectService;
import com.jarylee.medicalagent.safety.SensitiveContentPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectFileServiceTest {
    private final PlatformStore store = new PlatformStore();
    private final MutableCurrentUser currentUser = new MutableCurrentUser();
    private final MemoryObjectStorage storage = new MemoryObjectStorage();
    private final AuditService audit = new AuditService(new MemoryAuditRepository(store));
    private final ResearchProjectService projects = new ResearchProjectService(
            new MemoryProjectRepository(store), new MemoryProjectMemberRepository(store),
            new MemoryIdentityRepository(store), currentUser, audit);
    private final ProjectFileService files = new ProjectFileService(
            currentUser, projects, new UploadFileValidator(), new BasicMalwareScanner(),
            new DocumentTextExtractor(), new SensitiveContentPolicy(),
            storage, new MemoryProjectFileRepository(store), audit);

    @Test
    void storesInHospitalProjectQuarantinePathAndBlocksSensitiveTextFromExternalModel() {
        UUID hospitalId = UUID.randomUUID();
        currentUser.user = user(hospitalId, "doctor-a");
        var project = projects.create("FILE-001", "文件课题", "file-project");

        var uploaded = files.upload(project.id(), "../../患者资料.txt", "text/plain",
                "住院号：IP-123456".getBytes(StandardCharsets.UTF_8));

        assertThat(uploaded.originalName()).isEqualTo("患者资料.txt");
        assertThat(uploaded.securityStatus())
                .isEqualTo(SensitiveContentPolicy.Status.BLOCKED_FOR_EXTERNAL_MODEL);
        assertThat(uploaded.canSendToExternalModel()).isFalse();
        assertThat(uploaded.scanEngine()).isEqualTo("BASIC_SIGNATURE");
        assertThat(uploaded.extractionStatus()).isEqualTo("EXTRACTED");
        assertThat(uploaded.extractedCharacters()).isPositive();
        var row = store.files.get(uploaded.id());
        assertThat(row.objectKey()).startsWith(hospitalId + "/" + project.id() + "/quarantine/");
        assertThat(storage.get(row.objectKey())).isEqualTo("住院号：IP-123456".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void cannotUploadToAnotherHospitalsProject() {
        UUID hospitalA = UUID.randomUUID();
        currentUser.user = user(hospitalA, "doctor-a");
        var project = projects.create("FILE-002", "医院A课题", "file-project-a");
        currentUser.user = user(UUID.randomUUID(), "doctor-b");

        assertThatThrownBy(() -> files.upload(project.id(), "notes.txt", "text/plain",
                "匿名资料".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("课题不存在");
        assertThat(store.files).isEmpty();
    }

    @Test
    void rejectsMalwareBeforeObjectStorageAndMetadataWrites() {
        UUID hospitalId = UUID.randomUUID();
        currentUser.user = user(hospitalId, "doctor-a");
        var project = projects.create("FILE-003", "恶意文件验证", "file-project-malware");

        assertThatThrownBy(() -> files.upload(project.id(), "eicar.txt", "text/plain",
                "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("恶意文件扫描未通过");
        assertThat(store.files).isEmpty();
    }

    private AuthenticatedUser user(UUID hospitalId, String username) {
        return new AuthenticatedUser(UUID.randomUUID(), hospitalId, username, Set.of(Role.DOCTOR), false);
    }

    private static class MutableCurrentUser implements CurrentUserProvider {
        private AuthenticatedUser user;
        @Override public AuthenticatedUser requireUser() { return user; }
    }
}
