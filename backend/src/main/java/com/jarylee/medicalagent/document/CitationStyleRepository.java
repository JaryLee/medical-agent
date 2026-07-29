package com.jarylee.medicalagent.document;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CitationStyleRepository {
    int nextVersion(UUID hospitalId, String styleCode);

    StyleData create(StyleData style);

    List<StyleData> findAll(UUID hospitalId);

    Optional<StyleData> findById(UUID hospitalId, UUID styleId);

    Optional<StyleData> publish(
            UUID hospitalId, UUID styleId, UUID publishedBy,
            Instant publishedAt, long expectedVersion);

    record StyleData(
            UUID id,
            UUID hospitalId,
            String styleCode,
            String styleName,
            int versionNo,
            String status,
            String layout,
            int authorLimit,
            String etAlText,
            boolean includePmid,
            boolean includeDoi,
            boolean includeEvidenceScope,
            String evidenceScopeLabel,
            UUID createdBy,
            Instant createdAt,
            UUID publishedBy,
            Instant publishedAt,
            long version
    ) {}
}
