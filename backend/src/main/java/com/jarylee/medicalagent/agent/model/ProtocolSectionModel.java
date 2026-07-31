package com.jarylee.medicalagent.agent.model;

import java.util.List;

public final class ProtocolSectionModel {
    public static final String GENERATION_INPUT_SCHEMA =
            "protocol-section-generation-input/v1";
    public static final String GENERATION_OUTPUT_SCHEMA =
            "protocol-section-generation-candidate/v1";
    public static final String REVIEW_INPUT_SCHEMA =
            "protocol-section-review-input/v1";
    public static final String REVIEW_OUTPUT_SCHEMA =
            "protocol-section-review-advisory/v1";

    private ProtocolSectionModel() {}

    public record GenerationRequest(
            String schemaVersion,
            String studyType,
            String sectionCode,
            String sectionTitle,
            int baseVersionNo,
            String currentContent,
            List<String> confirmedFacts,
            List<String> allowedEvidenceIdentifiers,
            List<String> requiredLimitations
    ) {}

    public record GenerationCandidate(
            String schemaVersion,
            String sectionCode,
            String contentMarkdown,
            List<String> usedEvidenceIdentifiers,
            List<String> issuesToConfirm,
            List<String> limitations
    ) {}

    public record ReviewRequest(
            String schemaVersion,
            String studyType,
            String sectionCode,
            String contentMarkdown,
            List<String> allowedEvidenceIdentifiers,
            List<String> usedEvidenceIdentifiers
    ) {}

    public record ReviewAdvisory(
            String schemaVersion,
            String severity,
            List<ReviewIssue> issues,
            String summary,
            boolean advisoryOnly
    ) {}

    public record ReviewIssue(
            String type,
            String severity,
            String location,
            String message,
            String suggestedChange
    ) {}
}
