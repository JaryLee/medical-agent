package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StrobeCompletenessModels {
    private StrobeCompletenessModels() {}

    public record CheckItem(
            UUID itemResultId,
            String itemCode,
            String sectionGroup,
            String requirementSummary,
            StudyType studyType,
            String status,
            List<String> mappedSectionCodes,
            List<String> evidenceSnippets,
            String message,
            String suggestion,
            boolean requiresExpertReview
    ) {}

    public record CheckResult(
            String schemaVersion,
            UUID checkTaskId,
            UUID protocolId,
            Instant checkedAt,
            String guidelineCode,
            String guidelineVersion,
            StudyType studyType,
            int totalItemCount,
            int coveredCount,
            int partiallyCoveredCount,
            int missingCount,
            int notApplicableCount,
            int needsExpertReviewCount,
            List<CheckItem> items,
            String inputSha256,
            String checkerVersion,
            String sourceReference,
            String automaticPrecheckDisclaimer,
            List<String> limitations
    ) {}
}
