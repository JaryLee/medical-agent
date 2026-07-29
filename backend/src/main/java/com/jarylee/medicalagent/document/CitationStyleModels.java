package com.jarylee.medicalagent.document;

import java.time.Instant;
import java.util.UUID;

public final class CitationStyleModels {
    private CitationStyleModels() {}

    public record StyleView(
            UUID id,
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
