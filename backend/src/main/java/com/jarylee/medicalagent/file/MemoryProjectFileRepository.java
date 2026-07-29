package com.jarylee.medicalagent.file;

import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("memory")
public class MemoryProjectFileRepository implements ProjectFileRepository {
    private final PlatformStore store;

    public MemoryProjectFileRepository(PlatformStore store) {
        this.store = store;
    }

    @Override
    public void save(FileData file) {
        store.files.put(file.id(), new PlatformStore.FileRow(
                file.id(), file.hospitalId(), file.projectId(), file.originalName(),
                file.objectKey(), file.contentType(), file.sizeBytes(), file.sha256(),
                file.securityStatus(), file.matchedRules(), file.scanEngine(),
                file.extractedCharacters(), file.extractionStatus(), file.createdAt()));
    }
}
