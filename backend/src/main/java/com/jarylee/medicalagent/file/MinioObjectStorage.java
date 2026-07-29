package com.jarylee.medicalagent.file;

import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
@Profile("postgres")
@ConditionalOnProperty(name = "medical.storage.enabled", havingValue = "true")
public class MinioObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(
            @Value("${medical.storage.endpoint}") String endpoint,
            @Value("${medical.storage.access-key}") String accessKey,
            @Value("${medical.storage.secret-key}") String secretKey,
            @Value("${medical.storage.bucket}") String bucket) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalArgumentException("启用 MinIO 时必须配置访问凭据");
        }
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
        ensureBucket();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        try (var input = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, (long) content.length, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("对象存储写入失败", exception);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (var input = client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectKey).build())) {
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("对象存储读取失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new IllegalStateException("对象存储删除失败", exception);
        }
    }

    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO 存储桶初始化失败", exception);
        }
    }
}
