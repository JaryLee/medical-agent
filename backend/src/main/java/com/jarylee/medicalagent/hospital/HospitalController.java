package com.jarylee.medicalagent.hospital;

import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
public class HospitalController {
    private final HospitalService service;

    public HospitalController(HospitalService service) { this.service = service; }

    @PostMapping("/api/admin/hospitals")
    public ApiResponse<HospitalService.HospitalView> createHospital(
            @Valid @RequestBody CreateHospitalRequest request) {
        return ApiResponse.ok(service.createHospital(request.code(), request.name()));
    }

    @GetMapping("/api/admin/hospitals")
    public ApiResponse<List<HospitalService.HospitalView>> hospitals() {
        return ApiResponse.ok(service.listHospitals());
    }

    @PostMapping("/api/hospital/users")
    public ApiResponse<HospitalService.UserView> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(service.createUser(request.hospitalId(), request.username(),
                request.initialPassword(), request.roles()));
    }

    @GetMapping("/api/hospital/users")
    public ApiResponse<List<HospitalService.UserView>> users() {
        return ApiResponse.ok(service.listUsers());
    }

    @PostMapping("/api/hospital/users/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable UUID id) {
        service.disableUser(id);
        return ApiResponse.ok(null);
    }

    public record CreateHospitalRequest(@NotBlank String code, @NotBlank String name) {}
    public record CreateUserRequest(UUID hospitalId, @NotBlank String username,
                                    @NotBlank String initialPassword, @NotEmpty Set<Role> roles) {}
}
