package com.jarylee.medicalagent.file;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("memory")
public class MemoryObjectStorage implements ObjectStorage {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        objects.put(objectKey, content.clone());
    }

    @Override
    public byte[] get(String objectKey) {
        byte[] content = objects.get(objectKey);
        if (content == null) throw new IllegalArgumentException("对象不存在");
        return content.clone();
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(objectKey);
    }
}
