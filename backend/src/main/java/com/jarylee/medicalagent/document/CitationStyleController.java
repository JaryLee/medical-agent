package com.jarylee.medicalagent.document;

import com.jarylee.medicalagent.common.ApiResponse;
import com.jarylee.medicalagent.document.CitationStyleModels.StyleView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/citation-styles")
public class CitationStyleController {
    private final CitationStyleService service;

    public CitationStyleController(CitationStyleService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<StyleView>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/default")
    public ApiResponse<StyleView> installDefault() {
        return ApiResponse.ok(service.installDefault());
    }

    @PostMapping
    public ApiResponse<StyleView> create(
            @Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(
                request.styleCode(), request.styleName(), request.layout(),
                request.authorLimit(), request.etAlText(),
                request.includeDoi(), request.includeEvidenceScope(),
                request.evidenceScopeLabel()));
    }

    @PostMapping("/{styleId}/publish")
    public ApiResponse<StyleView> publish(
            @PathVariable UUID styleId,
            @Valid @RequestBody PublishRequest request) {
        return ApiResponse.ok(service.publish(
                styleId, request.expectedVersion()));
    }

    public record CreateRequest(
            @NotBlank String styleCode,
            @NotBlank String styleName,
            @NotBlank String layout,
            @Min(1) @Max(20) int authorLimit,
            @NotBlank String etAlText,
            boolean includeDoi,
            boolean includeEvidenceScope,
            @NotBlank String evidenceScopeLabel
    ) {}

    public record PublishRequest(@NotNull Long expectedVersion) {}
}
