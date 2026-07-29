package com.jarylee.medicalagent.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "liveMinio", matches = "true")
class MinioObjectStorageLiveTest {

    @Test
    void writesReadsAndDeletesObjectOnLocalMinio() {
        String accessKey = requiredEnvironment("MINIO_ACCESS_KEY");
        String secretKey = requiredEnvironment("MINIO_SECRET_KEY");
        var storage = new MinioObjectStorage(
                "http://127.0.0.1:9000", accessKey, secretKey, "medical-agent-files");
        String objectKey = "live-test/" + UUID.randomUUID() + "/verification.txt";
        byte[] content = "local MinIO verified".getBytes(StandardCharsets.UTF_8);
        try {
            storage.put(objectKey, content, "text/plain");
            assertThat(storage.get(objectKey)).isEqualTo(content);
        } finally {
            storage.delete(objectKey);
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量 " + name);
        return value;
    }
}
