package com.jarylee.medicalagent.document;

import com.jarylee.medicalagent.common.ApiResponse;
import com.jarylee.medicalagent.document.DocumentExportModels.ExportView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
public class DocumentExportController {
    private final DocumentExportService service;

    public DocumentExportController(DocumentExportService service) {
        this.service = service;
    }

    @GetMapping("/api/agent/tasks/{agentTaskId}/document-export")
    public ApiResponse<ExportView> get(@PathVariable UUID agentTaskId) {
        return ApiResponse.ok(service.getByTask(agentTaskId));
    }

    @PostMapping("/api/agent/tasks/{agentTaskId}/document-export")
    public ApiResponse<ExportView> export(
            @PathVariable UUID agentTaskId,
            @Valid @RequestBody ExportRequest request) {
        return ApiResponse.ok(service.confirmAndExport(
                agentTaskId, request.templateVersionId(),
                request.citationStyleVersionId(),
                request.confirmReviewedContent()));
    }

    @GetMapping("/api/document-exports/{exportId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID exportId) {
        var file = service.download(exportId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-SHA256", file.sha256())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        file.contentType()))
                .body(file.content());
    }

    public record ExportRequest(
            @NotNull UUID templateVersionId,
            @NotNull UUID citationStyleVersionId,
            @AssertTrue(message = "必须确认导出内容来自已审核锁定版本")
            boolean confirmReviewedContent
    ) {}
}
