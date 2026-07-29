package com.jarylee.medicalagent.file;

import com.jarylee.medicalagent.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/research/projects/{projectId}/files")
public class ProjectFileController {
    private final ProjectFileService service;

    public ProjectFileController(ProjectFileService service) {
        this.service = service;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<ProjectFileService.FileView> upload(
            @PathVariable UUID projectId, @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(service.upload(
                projectId, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }
}
