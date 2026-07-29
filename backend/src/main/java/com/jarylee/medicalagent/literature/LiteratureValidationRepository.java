package com.jarylee.medicalagent.literature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LiteratureValidationRepository {
    void create(ValidationData validation);

    void complete(
            ValidationData validation,
            List<LiteratureValidationModels.CitationValidation> citations,
            List<LiteratureValidationModels.EvidenceLink> evidenceLinks);

    void fail(
            UUID hospitalId, UUID validationId, String errorCode,
            String errorMessage, Instant completedAt);

    record ValidationData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            String status,
            Instant startedAt,
            Instant completedAt,
            Integer validationCount,
            Integer evidenceLinkCount,
            String rawObjectKey,
            String rawResponseSha256,
            String rawContentType,
            String toolVersion,
            Integer externalRequestCount,
            Integer cacheHitCount,
            String errorCode,
            String errorMessage
    ) {}
}
