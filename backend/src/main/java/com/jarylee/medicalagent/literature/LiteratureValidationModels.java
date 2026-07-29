package com.jarylee.medicalagent.literature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LiteratureValidationModels {
    private LiteratureValidationModels() {}

    public record FieldCheck(
            String field,
            String status,
            String pubmedValue,
            String crossrefValue
    ) {}

    public record CitationValidation(
            String pmid,
            String doi,
            String status,
            String validationSource,
            List<FieldCheck> fieldChecks,
            CrossrefMetadataModels.Work crossrefMetadata,
            String message
    ) {}

    public record EvidenceLink(
            String nctId,
            String pmid,
            String relationship,
            String status
    ) {}

    public record ValidationResult(
            String schemaVersion,
            UUID validationTaskId,
            Instant validatedAt,
            int totalCount,
            int verifiedCount,
            int metadataDifferenceCount,
            int mismatchCount,
            int crossrefNotFoundCount,
            int doiNotAvailableCount,
            List<CitationValidation> citations,
            List<EvidenceLink> evidenceLinks,
            String rawResponseSha256,
            String rawContentType,
            String toolVersion,
            int externalRequestCount,
            int cacheHitCount,
            List<String> limitations
    ) {}

    public record RawEvidence(
            String pmid,
            String doi,
            boolean found,
            String contentType,
            byte[] response
    ) {}
}
