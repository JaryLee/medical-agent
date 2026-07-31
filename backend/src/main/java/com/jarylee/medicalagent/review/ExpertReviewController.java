package com.jarylee.medicalagent.review;

import com.jarylee.medicalagent.common.ApiResponse;
import com.jarylee.medicalagent.review.ExpertReviewModels.CommentType;
import com.jarylee.medicalagent.review.ExpertReviewModels.Decision;
import com.jarylee.medicalagent.review.ExpertReviewModels.Responsibility;
import com.jarylee.medicalagent.review.ExpertReviewModels.ReviewView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/agent/tasks/{agentTaskId}/expert-review")
public class ExpertReviewController {
    private final ExpertReviewService service;

    public ExpertReviewController(ExpertReviewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ReviewView> get(@PathVariable UUID agentTaskId) {
        return ApiResponse.ok(service.get(agentTaskId));
    }

    @PostMapping("/comments")
    public ApiResponse<ReviewView> addComment(
            @PathVariable UUID agentTaskId,
            @Valid @RequestBody AddCommentRequest request) {
        return ApiResponse.ok(service.addComment(
                agentTaskId, request.protocolSectionId(),
                request.protocolSectionVersionNo(), request.strobeItemResultId(),
                request.commentType(), request.responsibility(), request.content()));
    }

    @PostMapping("/decision")
    public ApiResponse<ReviewView> decide(
            @PathVariable UUID agentTaskId,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(service.decide(
                agentTaskId, request.responsibility(), request.decision(), request.summary(),
                request.expectedVersion()));
    }

    @PostMapping("/owner-confirmation")
    public ApiResponse<ReviewView> ownerConfirm(
            @PathVariable UUID agentTaskId,
            @Valid @RequestBody OwnerConfirmationRequest request) {
        return ApiResponse.ok(service.ownerConfirm(
                agentTaskId, request.expectedVersion()));
    }

    public record AddCommentRequest(
            UUID protocolSectionId,
            @Positive Integer protocolSectionVersionNo,
            UUID strobeItemResultId,
            @NotNull CommentType commentType,
            @NotNull Responsibility responsibility,
            @NotBlank @Size(max = 2000) String content
    ) {}

    public record DecisionRequest(
            @NotNull Responsibility responsibility,
            @NotNull Decision decision,
            @NotBlank @Size(max = 2000) String summary,
            @PositiveOrZero long expectedVersion
    ) {}

    public record OwnerConfirmationRequest(
            @PositiveOrZero long expectedVersion
    ) {}
}
