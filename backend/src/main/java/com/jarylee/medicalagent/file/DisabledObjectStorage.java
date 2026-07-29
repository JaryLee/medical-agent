package com.jarylee.medicalagent.file;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres")
@ConditionalOnProperty(name = "medical.storage.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledObjectStorage implements ObjectStorage {
    private IllegalStateException disabled() {
        return new IllegalStateException("MinIO 未启用");
    }

    @Override public void put(String objectKey, byte[] content, String contentType) { throw disabled(); }
    @Override public byte[] get(String objectKey) { throw disabled(); }
    @Override public void delete(String objectKey) { throw disabled(); }
    @Override public boolean isAvailable() { return false; }
}
