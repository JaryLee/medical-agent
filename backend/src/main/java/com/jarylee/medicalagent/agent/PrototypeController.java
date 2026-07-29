package com.jarylee.medicalagent.agent;

import com.jarylee.medicalagent.agent.model.ResearchModels.*;
import com.jarylee.medicalagent.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/prototype")
public class PrototypeController {
    private final PrototypeService service;

    public PrototypeController(PrototypeService service) {
        this.service = service;
    }

    @PostMapping("/ideas/analyze")
    public ApiResponse<AnalysisResult> analyze(@Valid @RequestBody AnalyzeIdeaRequest request) {
        return ApiResponse.ok(service.analyze(request.idea()));
    }

    @PostMapping("/directions/confirm")
    public ApiResponse<PrototypeResult> confirm(@Valid @RequestBody ConfirmDirectionRequest request) {
        return ApiResponse.ok(service.run(request.idea(), request.directionId()));
    }

    @PostMapping("/directions/confirm/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ConfirmDirectionRequest request) throws IOException {
        byte[] content = service.export(request.idea(), request.directionId());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("anonymous-research-protocol.docx", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(content);
    }
}
