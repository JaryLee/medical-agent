package com.jarylee.medicalagent.literature;

import java.util.List;

public final class CrossrefMetadataModels {
    private CrossrefMetadataModels() {}

    public record Work(
            String doi,
            String title,
            List<String> authors,
            String journal,
            String publicationDate,
            String type,
            String publisher
    ) {}

    public record GatewayResult(
            boolean found,
            Work work,
            byte[] rawResponse,
            String rawContentType,
            String toolVersion,
            int externalRequestCount,
            boolean cacheHit
    ) {}
}
