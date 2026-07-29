package com.jarylee.medicalagent.file;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.research.ResearchProjectService;
import com.jarylee.medicalagent.safety.SensitiveContentPolicy;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectFileService {
    private final CurrentUserProvider currentUser;
    private final ResearchProjectService projects;
    private final UploadFileValidator validator;
    private final MalwareScanner malwareScanner;
    private final DocumentTextExtractor textExtractor;
    private final SensitiveContentPolicy sensitiveContent;
    private final ObjectStorage storage;
    private final ProjectFileRepository repository;
    private final AuditService audit;

    public ProjectFileService(CurrentUserProvider currentUser, ResearchProjectService projects,
                              UploadFileValidator validator, MalwareScanner malwareScanner,
                              DocumentTextExtractor textExtractor, SensitiveContentPolicy sensitiveContent,
                              ObjectStorage storage, ProjectFileRepository repository, AuditService audit) {
        this.currentUser = currentUser;
        this.projects = projects;
        this.validator = validator;
        this.malwareScanner = malwareScanner;
        this.textExtractor = textExtractor;
        this.sensitiveContent = sensitiveContent;
        this.storage = storage;
        this.repository = repository;
        this.audit = audit;
    }

    public FileView upload(UUID projectId, String originalName, String contentType, byte[] content) {
        AuthenticatedUser actor = currentUser.requireUser();
        if (actor.forcePasswordChange()) throw BusinessException.forbidden("首次登录必须先修改密码");
        if (actor.hospitalId() == null) throw BusinessException.forbidden("平台管理员不能上传医院课题文件");
        projects.requireEditable(projectId);
        var validated = validator.validate(originalName, contentType, content);
        MalwareScanner.ScanResult scan = malwareScanner.scan(
                validated.safeName(), validated.contentType(), validated.content());
        if (!scan.clean()) throw new IllegalArgumentException("恶意文件扫描未通过");

        DocumentTextExtractor.Extraction extraction = validated.extractedText() == null
                ? textExtractor.extract(validated.extension(), validated.content())
                : extractionOf(validated.extractedText());
        SensitiveContentPolicy.Assessment assessment = extraction.text().isEmpty()
                ? new SensitiveContentPolicy.Assessment(
                        SensitiveContentPolicy.Status.REQUIRES_ADMIN_REVIEW, List.of("TEXT_EXTRACTION_EMPTY"))
                : sensitiveContent.assess(extraction.text());

        UUID fileId = UUID.randomUUID();
        String objectKey = "%s/%s/quarantine/%s/%s".formatted(
                actor.hospitalId(), projectId, fileId, validated.safeName());
        String sha256 = sha256(validated.content());
        var row = new ProjectFileRepository.FileData(
                fileId, actor.hospitalId(), projectId, validated.safeName(), objectKey,
                validated.contentType(), validated.content().length, sha256,
                assessment.status().name(), String.join(",", assessment.matchedRules()),
                scan.engine(), extraction.text().length(), extraction.status(), Instant.now());

        storage.put(objectKey, validated.content(), validated.contentType());
        try {
            repository.save(row);
        } catch (RuntimeException exception) {
            try {
                storage.delete(objectKey);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        audit.record(actor, "PROJECT_FILE_UPLOADED", "PROJECT_FILE", fileId.toString());
        return new FileView(fileId, projectId, validated.safeName(), validated.contentType(),
                validated.content().length, sha256, assessment.status(), assessment.matchedRules(),
                assessment.canSendToExternalModel(), scan.engine(),
                extraction.text().length(), extraction.status());
    }

    private DocumentTextExtractor.Extraction extractionOf(String text) {
        String normalized = text.strip();
        return new DocumentTextExtractor.Extraction(
                normalized, normalized.isEmpty() ? "EMPTY" : "EXTRACTED");
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算文件摘要", exception);
        }
    }

    public record FileView(UUID id, UUID projectId, String originalName, String contentType,
                           long sizeBytes, String sha256, SensitiveContentPolicy.Status securityStatus,
                           List<String> matchedRules, boolean canSendToExternalModel,
                           String scanEngine, int extractedCharacters, String extractionStatus) {}
}
