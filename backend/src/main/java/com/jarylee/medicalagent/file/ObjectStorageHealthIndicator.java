package com.jarylee.medicalagent.file;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ObjectStorageHealthIndicator implements HealthIndicator {
    private final ObjectStorage objectStorage;

    public ObjectStorageHealthIndicator(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Override
    public Health health() {
        return objectStorage.isAvailable() ? Health.up().build() : Health.down().build();
    }
}
