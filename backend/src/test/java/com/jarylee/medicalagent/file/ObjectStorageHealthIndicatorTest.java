package com.jarylee.medicalagent.file;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageHealthIndicatorTest {
    @Test
    void reportsStorageAvailabilityWithoutExposingDetails() {
        var available = new ObjectStorageHealthIndicator(storage(true)).health();
        var unavailable = new ObjectStorageHealthIndicator(storage(false)).health();

        assertThat(available.getStatus()).isEqualTo(Status.UP);
        assertThat(available.getDetails()).isEmpty();
        assertThat(unavailable.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unavailable.getDetails()).isEmpty();
    }

    private ObjectStorage storage(boolean available) {
        return new ObjectStorage() {
            @Override public void put(String objectKey, byte[] content, String contentType) {}
            @Override public byte[] get(String objectKey) { return new byte[0]; }
            @Override public void delete(String objectKey) {}
            @Override public boolean isAvailable() { return available; }
        };
    }
}
