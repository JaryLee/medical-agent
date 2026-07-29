package com.jarylee.medicalagent.literature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ClinicalTrialsSearchModels {
    private ClinicalTrialsSearchModels() {}

    public record Trial(
            String nctId,
            String briefTitle,
            String officialTitle,
            String overallStatus,
            String studyType,
            List<String> phases,
            List<String> conditions,
            List<String> interventions,
            String briefSummary,
            List<String> primaryOutcomes,
            String leadSponsor,
            String startDate,
            String completionDate,
            Integer enrollment,
            List<String> countries,
            boolean hasResults,
            String evidenceScope,
            boolean verified,
            String source,
            List<String> linkedPmids
    ) {}

    public record GatewayResult(
            long totalResultCount,
            List<Trial> trials,
            byte[] rawResponse,
            String rawContentType,
            String toolVersion,
            int externalRequestCount,
            String dataVersion,
            boolean cacheHit
    ) {}

    public record SearchResult(
            String schemaVersion,
            UUID searchRecordId,
            String database,
            String sourceType,
            String query,
            String queryVersion,
            Instant searchedAt,
            long totalResultCount,
            int returnedCount,
            List<Trial> records,
            String rawResponseSha256,
            String rawContentType,
            String toolVersion,
            int externalRequestCount,
            String dataVersion,
            boolean cacheHit,
            List<String> limitations
    ) {}
}
