package com.jarylee.medicalagent.research;

import com.jarylee.medicalagent.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/research/projects")
public class ResearchProjectController {
    private final ResearchProjectService service;

    public ResearchProjectController(ResearchProjectService service) { this.service = service; }

    @PostMapping
    public ApiResponse<ResearchProjectService.ProjectView> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(service.create(request.code(), request.name(), idempotencyKey));
    }

    @GetMapping
    public ApiResponse<List<ResearchProjectService.ProjectView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ResearchProjectService.ProjectView> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ResearchProjectService.ProjectView> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateProjectRequest request) {
        return ApiResponse.ok(service.update(id, request.name(), request.version()));
    }

    @PostMapping("/{id}/members")
    public ApiResponse<ResearchProjectService.MemberView> addMember(
            @PathVariable UUID id, @Valid @RequestBody AddMemberRequest request) {
        return ApiResponse.ok(service.addMember(id, request.userId(), request.role()));
    }

    @GetMapping("/{id}/members")
    public ApiResponse<List<ResearchProjectService.MemberView>> members(@PathVariable UUID id) {
        return ApiResponse.ok(service.listMembers(id));
    }

    public record CreateProjectRequest(@NotBlank String code, @NotBlank String name) {}
    public record UpdateProjectRequest(@NotBlank String name, long version) {}
    public record AddMemberRequest(@jakarta.validation.constraints.NotNull UUID userId,
                                   @jakarta.validation.constraints.NotNull ProjectMemberRole role) {}
}
