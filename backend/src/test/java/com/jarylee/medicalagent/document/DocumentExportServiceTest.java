package com.jarylee.medicalagent.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.MemoryAuditRepository;
import com.jarylee.medicalagent.auth.*;
import com.jarylee.medicalagent.file.MemoryObjectStorage;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import com.jarylee.medicalagent.research.*;
import com.jarylee.medicalagent.review.ExpertReviewRepository;
import com.jarylee.medicalagent.review.MemoryExpertReviewRepository;
import com.jarylee.medicalagent.workflow.AgentEventStream;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.MemoryAgentWorkflowRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentExportServiceTest {
    private final PlatformStore store = new PlatformStore();
    private final MutableCurrentUser currentUser = new MutableCurrentUser();
    private final MemoryIdentityRepository identities =
            new MemoryIdentityRepository(store);
    private final MemoryProjectMemberRepository members =
            new MemoryProjectMemberRepository(store);
    private final AuditService audit =
            new AuditService(new MemoryAuditRepository(store));
    private final ResearchProjectService projects = new ResearchProjectService(
            new MemoryProjectRepository(store), members, identities,
            currentUser, audit);
    private final MemoryAgentWorkflowRepository workflows =
            new MemoryAgentWorkflowRepository();
    private final MemoryExpertReviewRepository reviews =
            new MemoryExpertReviewRepository();
    private final MemoryDocumentExportRepository exportRepository =
            new MemoryDocumentExportRepository();
    private final MemoryCitationStyleRepository citationRepository =
            new MemoryCitationStyleRepository();
    private final MemoryObjectStorage storage = new MemoryObjectStorage();
    private final ObjectMapper json =
            new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC);
    private final ControlledDocxTemplateEngine engine =
            new ControlledDocxTemplateEngine();
    private final DocumentTemplateService templateService =
            new DocumentTemplateService(
                    exportRepository, engine, storage, currentUser,
                    audit, json, clock);
    private final CitationStyleService citationStyleService =
            new CitationStyleService(
                    citationRepository, currentUser, audit, clock);
    private final DocumentExportService service = new DocumentExportService(
            exportRepository, templateService, citationStyleService,
            engine, workflows, reviews, projects, identities,
            storage, currentUser, audit,
            new AgentEventStream(), json, clock);

    @Test
    void exportsOnlyLockedApprovedSnapshotAndCompletesStep18() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalId, "DOCX", "文档测试医院", clock.instant()));
        identities.insertUser(new IdentityRepository.UserData(
                ownerId, hospitalId, "owner",
                new BCryptPasswordEncoder().encode("InitialPass123"),
                Set.of(Role.DOCTOR, Role.HOSPITAL_ADMIN),
                true, false, 0, null));
        currentUser.user = new AuthenticatedUser(
                ownerId, hospitalId, "owner",
                Set.of(Role.DOCTOR, Role.HOSPITAL_ADMIN), false);
        var project = projects.create(
                "DOCX-001", "匿名队列研究", "docx-project");
        byte[] structuredTemplate;
        try (var input = new ClassPathResource(
                "templates/synthetic-hospital-b-protocol.docx")
                .getInputStream()) {
            structuredTemplate = input.readAllBytes();
        }
        var validated = templateService.upload(
                "SYNTHETIC_B",
                "合成结构化回归模板",
                new MockMultipartFile(
                        "file",
                        "synthetic-hospital-b-protocol.docx",
                        "application/vnd.openxmlformats-officedocument"
                                + ".wordprocessingml.document",
                        structuredTemplate));
        var preview = templateService.preview(validated.id());
        assertThat(preview.content()).isNotEmpty();
        assertThat(preview.sha256()).hasSize(64);
        try (var previewDocument = new XWPFDocument(
                new ByteArrayInputStream(preview.content()))) {
            String previewText = previewDocument.getParagraphs().stream()
                    .map(value -> value.getText())
                    .reduce("", (left, right) -> left + "\n" + right)
                    + previewDocument.getTables().stream()
                    .map(value -> value.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(previewText)
                    .contains("模板试生成")
                    .doesNotContain("${");
        }
        var published = templateService.publish(
                validated.id(), validated.version());
        var validatedStyle = citationStyleService.create(
                "HOSPITAL_GBT",
                "医院 GB/T 7714 数字格式",
                "GB_T_7714",
                3,
                "等",
                true,
                true,
                "摘要级证据");
        var publishedStyle = citationStyleService.publish(
                validatedStyle.id(), validatedStyle.version());

        UUID taskId = UUID.randomUUID();
        UUID protocolId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID strobeId = UUID.randomUUID();
        String output = json.writeValueAsString(Map.of(
                "peco", Map.of(
                        "researchQuestion", "暴露与结局是否相关",
                        "population", "匿名研究对象",
                        "outcome", "匿名主要终点"),
                "protocolDraft", Map.of(
                        "protocolId", protocolId,
                        "title", "匿名队列研究方案",
                        "studyType", "COHORT",
                        "sections", List.of(
                                section("BACKGROUND", "研究背景", List.of("PMID:123")),
                                section("OBJECTIVES", "研究目标", List.of()),
                                section("STUDY_DESIGN", "队列研究设计", List.of()),
                                section("PARTICIPANTS", "匿名研究对象", List.of()),
                                section("ELIGIBILITY", "纳入与排除标准", List.of()),
                                section("OUTCOMES_VARIABLES", "主要终点", List.of()),
                                section("STATISTICAL_ANALYSIS", "统计分析计划", List.of()),
                                section("ETHICS_DATA_SECURITY", "伦理与数据安全", List.of()),
                                section("REFERENCES", "参考文献", List.of("PMID:123")))),
                "literatureValidation", Map.of(
                        "citations", List.of(Map.of(
                                "pmid", "123", "status", "VERIFIED"))),
                "pubmedSearch", Map.of(
                        "records", List.of(Map.of(
                                "pmid", "123",
                                "doi", "10.1000/demo",
                                "title", "Verified anonymous research",
                                "authors", List.of("Zhang A", "Li B"),
                                "journal", "Demo Journal",
                                "publicationDate", "2026")))));
        Instant now = clock.instant();
        workflows.create(new AgentWorkflowRepository.TaskData(
                taskId, hospitalId, project.id(), ownerId,
                "STEP_18_EXPORT_DOCUMENT", "WAITING_CONFIRMATION",
                "{}", output, null, now.plusSeconds(900), false, 0,
                null, null, now, now, null), "docx-task");
        reviews.create(new ExpertReviewRepository.ReviewTaskData(
                reviewId, hospitalId, project.id(), taskId, protocolId,
                strobeId, "APPROVED", ownerId, now, UUID.randomUUID(),
                "APPROVE", "通过", now, ownerId, now, true, 2));

        var exported = service.confirmAndExport(
                taskId, published.id(), publishedStyle.id(), true);

        assertThat(exported.status()).isEqualTo("COMPLETED");
        assertThat(exported.citationCount()).isEqualTo(1);
        assertThat(exported.citationStyleVersionId())
                .isEqualTo(publishedStyle.id());
        assertThat(exported.citationStyleVersion())
                .isEqualTo("HOSPITAL_GBT/v1");
        assertThat(exported.contentSha256()).hasSize(64);
        assertThat(workflows.findById(hospitalId, taskId).orElseThrow().status())
                .isEqualTo("COMPLETED");
        var download = service.download(exported.id());
        assertThat(download.sha256()).isEqualTo(exported.contentSha256());
        String qaPath = System.getProperty("exportQaPath", "");
        if (!qaPath.isBlank()) {
            var path = java.nio.file.Path.of(qaPath).toAbsolutePath();
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, download.content());
        }
        try (var document = new XWPFDocument(
                new ByteArrayInputStream(download.content()))) {
            String text = document.getParagraphs().stream()
                    .map(value -> value.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            String tableText = document.getTables().stream()
                    .map(value -> value.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(text + tableText)
                    .contains("匿名队列研究方案")
                    .contains("[J]")
                    .contains("PMID:123")
                    .doesNotContain("${");
            assertThat(document.getTables().getLast().getRows()).hasSize(2);
        }
    }

    private Map<String, Object> section(
            String code, String content, List<String> sources) {
        return Map.of(
                "sectionCode", code,
                "content", content,
                "sourceIdentifiers", sources);
    }

    private static final class MutableCurrentUser implements CurrentUserProvider {
        private AuthenticatedUser user;

        @Override
        public AuthenticatedUser requireUser() {
            return user;
        }
    }
}
