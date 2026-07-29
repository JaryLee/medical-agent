package com.jarylee.medicalagent.literature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LiteratureSearchRepository {
    void create(SearchData search);
    void complete(SearchData search, List<PubMedSearchModels.Article> articles);
    void fail(UUID hospitalId, UUID searchId, String errorCode,
              String errorMessage, Instant completedAt);

    record SearchData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            String database,
            String originalQuestion,
            String structuredConceptsJson,
            String query,
            String queryVersion,
            String filtersJson,
            String status,
            Instant startedAt,
            Instant completedAt,
            Long totalResultCount,
            Integer returnedResultCount,
            String rawObjectKey,
            String rawResponseSha256,
            String rawContentType,
            String toolVersion,
            Integer externalRequestCount,
            String errorCode,
            String errorMessage
    ) {}
}
