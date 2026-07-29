package com.jarylee.medicalagent.literature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PubMedSearchModels {
    private PubMedSearchModels() {}

    public record Article(
            String pmid,
            String doi,
            String title,
            List<String> authors,
            String journal,
            String publicationDate,
            String abstractText,
            String evidenceScope,
            boolean verified,
            String source
    ) {}

    public record GatewayResult(
            long totalResultCount,
            List<Article> articles,
            byte[] rawResponse,
            String rawContentType,
            String toolVersion,
            int externalRequestCount,
            String historyWebEnv,
            String historyQueryKey
    ) {}

    public record SearchResult(
            String schemaVersion,
            UUID searchRecordId,
            String database,
            String query,
            String queryVersion,
            Instant searchedAt,
            long totalResultCount,
            int returnedCount,
            List<Article> records,
            String rawResponseSha256,
            String rawContentType,
            String toolVersion,
            int externalRequestCount,
            List<String> limitations
    ) {}
}
