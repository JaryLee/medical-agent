package com.jarylee.medicalagent.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ResearchModels {
    private ResearchModels() {}

    public record AnalyzeIdeaRequest(
            @NotBlank @Size(max = 2000) String idea
    ) {}

    public record ResearchIdeaProfile(
            String schemaVersion,
            String specialty,
            String clinicalProblem,
            String population,
            String exposure,
            String comparator,
            String outcome,
            String timeFrame,
            String setting,
            String researchPurpose,
            List<String> missingInformation
    ) {}

    public record ResearchDirection(
            String id,
            String title,
            StudyType recommendedStudyType,
            String researchPurpose,
            String population,
            String exposure,
            String outcome,
            List<String> dataRequirements,
            List<String> limitations
    ) {}

    public enum StudyType {
        CROSS_SECTIONAL, COHORT, CASE_CONTROL
    }

    public record AnalysisResult(
            String schemaVersion,
            ResearchIdeaProfile profile,
            List<String> clarificationQuestions,
            List<ResearchDirection> directions,
            String disclaimer
    ) {}

    public record ConfirmDirectionRequest(
            @NotBlank @Size(max = 2000) String idea,
            @NotBlank String directionId
    ) {}

    public record PecoDefinition(
            String schemaVersion,
            String population,
            String exposure,
            String comparator,
            String outcome,
            String researchQuestion,
            StudyType studyType,
            List<String> requiresExpertConfirmation
    ) {}

    public record LiteratureRecord(
            String citationId,
            String pmid,
            String doi,
            String title,
            List<String> authors,
            String journal,
            String publicationDate,
            String evidenceScope,
            boolean verified,
            String source
    ) {}

    public record ResearchQuestionResult(
            AnalysisResult analysis,
            ResearchDirection selectedDirection,
            PecoDefinition peco
    ) {}

    public record PrototypeResult(
            AnalysisResult analysis,
            ResearchDirection selectedDirection,
            PecoDefinition peco,
            String searchQuery,
            List<LiteratureRecord> literature,
            String background,
            String evidenceDisclaimer
    ) {}
}
