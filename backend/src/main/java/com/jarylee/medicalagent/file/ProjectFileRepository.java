package com.jarylee.medicalagent.file;

import java.time.Instant;
import java.util.UUID;

public interface ProjectFileRepository {
    void save(FileData file);

    record FileData(UUID id, UUID hospitalId, UUID projectId, String originalName,
                    String objectKey, String contentType, long sizeBytes, String sha256,
                    String securityStatus, String matchedRules, String scanEngine,
                    int extractedCharacters, String extractionStatus, Instant createdAt) {}
}
