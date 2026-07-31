package com.jarylee.medicalagent.workspace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WorkspaceModels {
    private WorkspaceModels() {}

    public record LabeledCode(String code, String label) {}

    public record Progress(int completed, int total, int percent) {}

    public record AllowedAction(
            String code,
            String label,
            boolean enabled,
            String reasonCode,
            String reason,
            String targetRoute) {}

    public record BlockedReason(String code, String message) {}

    public record StageView(
            String code,
            String label,
            String status,
            String summary,
            String targetRoute,
            List<String> blockedReasonCodes,
            Instant completedAt) {}

    public record WorkspaceSummary(
            String projectKey,
            String displayName,
            LabeledCode businessStatus,
            StageView currentStage,
            Progress progress,
            AllowedAction nextAction,
            List<AllowedAction> allowedActions,
            List<BlockedReason> blockedReasons,
            int pendingTodoCount,
            Instant lastUpdatedAt) {}

    public record TodoItem(
            String todoKey,
            String projectKey,
            LabeledCode todoType,
            String title,
            String description,
            String assigneeRole,
            String targetRoute,
            Instant dueAt,
            String status) {}

    public record ResponseMeta(
            long readModelVersion,
            Instant asOf,
            long latestEventId) {}

    public record Envelope<T>(T data, ResponseMeta meta) {}

    public record Page<T>(List<T> items, String nextCursor) {}

    public record ProjectEvent(
            long eventId,
            String type,
            String projectKey,
            long readModelVersion,
            Instant occurredAt) {}

    public record ResearchIdea(
            String content,
            String statusLabel) {}

    public record ClarificationRound(
            int roundNo,
            List<String> questions,
            Map<String, String> answers,
            Instant submittedAt) {}

    public record DirectionCandidate(
            String directionKey,
            String title,
            LabeledCode recommendedStudyType,
            List<String> limitations,
            boolean selected) {}

    public record DirectionCandidateSet(
            String candidateSetKey,
            String schemaVersion,
            List<DirectionCandidate> candidates) {}

    public record IdeaDirectionView(
            String projectKey,
            LabeledCode workflowStatus,
            ResearchIdea idea,
            List<String> currentClarificationQuestions,
            List<ClarificationRound> clarificationHistory,
            DirectionCandidateSet directionCandidates,
            List<AllowedAction> allowedActions,
            String disclaimer) {}
}
