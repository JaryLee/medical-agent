package com.jarylee.medicalagent.audit;

import com.jarylee.medicalagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audits")
public class AuditController {
    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AuditQueryService.AuditView>> recent(
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(service.recent(limit));
    }
}
