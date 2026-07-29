package com.jarylee.medicalagent.document;

import com.jarylee.medicalagent.common.ApiResponse;
import com.jarylee.medicalagent.document.DocumentExportModels.TemplateView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/document-templates")
public class DocumentTemplateController {
    private final DocumentTemplateService service;

    public DocumentTemplateController(DocumentTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<TemplateView>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/default")
    public ApiResponse<TemplateView> installDefault() {
        return ApiResponse.ok(service.installDefault());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TemplateView> upload(
            @RequestParam String templateCode,
            @RequestParam String templateName,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(service.upload(templateCode, templateName, file));
    }

    @PostMapping("/{templateId}/publish")
    public ApiResponse<TemplateView> publish(
            @PathVariable UUID templateId,
            @Valid @RequestBody PublishRequest request) {
        return ApiResponse.ok(service.publish(
                templateId, request.expectedVersion()));
    }

    @PostMapping("/{templateId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable UUID templateId) {
        var file = service.preview(templateId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-SHA256", file.sha256())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    public record PublishRequest(
            @NotNull @PositiveOrZero Long expectedVersion
    ) {}
}
