package com.jarylee.medicalagent.literature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SimilarResearchAnalysisModels {
    private SimilarResearchAnalysisModels() {}

    public record DimensionMatch(
            String dimension,
            boolean matched,
            int weight,
            List<String> matchedTerms
    ) {}

    public record SimilarResearch(
            String sourceType,
            String sourceIdentifier,
            String pmid,
            String doi,
            String nctId,
            String title,
            String publicationOrCompletionDate,
            int similarityScore,
            String similarityTier,
            String verificationStatus,
            String evidenceScope,
            List<DimensionMatch> dimensions,
            List<String> differences,
            List<String> linkedSourceIdentifiers
    ) {}

    public record ResearchGap(
            String code,
            String statement,
            String basis,
            List<String> basisSourceIdentifiers
    ) {}

    public record AnalysisResult(
            String schemaVersion,
            UUID analysisTaskId,
            Instant analyzedAt,
            String researchQuestion,
            List<String> databaseScope,
            int analyzedSourceCount,
            int excludedCitationCount,
            int highSimilarityCount,
            int moderateSimilarityCount,
            int lowSimilarityCount,
            List<SimilarResearch> similarResearch,
            List<ResearchGap> potentialResearchGaps,
            String conclusion,
            String inputSha256,
            String algorithmVersion,
            List<String> limitations
    ) {}
}
