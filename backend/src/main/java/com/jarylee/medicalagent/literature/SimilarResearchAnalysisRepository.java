package com.jarylee.medicalagent.literature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SimilarResearchAnalysisRepository {
    void create(AnalysisData analysis);

    void complete(
            AnalysisData analysis,
            List<SimilarResearchAnalysisModels.SimilarResearch> comparisons,
            List<SimilarResearchAnalysisModels.ResearchGap> gaps);

    void fail(
            UUID hospitalId, UUID analysisId, String errorCode,
            String errorMessage, Instant completedAt);

    record AnalysisData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            String status,
            Instant startedAt,
            Instant completedAt,
            Integer analyzedSourceCount,
            Integer excludedCitationCount,
            Integer highSimilarityCount,
            Integer moderateSimilarityCount,
            Integer lowSimilarityCount,
            Integer gapCount,
            String inputSha256,
            String algorithmVersion,
            String databaseScopeJson,
            String conclusion,
            String resultJson,
            String errorCode,
            String errorMessage
    ) {}
}
