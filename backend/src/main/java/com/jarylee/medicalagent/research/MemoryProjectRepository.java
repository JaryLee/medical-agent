package com.jarylee.medicalagent.research;

import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@Profile("memory")
public class MemoryProjectRepository implements ProjectRepository {
    private final PlatformStore store;

    public MemoryProjectRepository(PlatformStore store) { this.store = store; }

    @Override
    public Optional<ProjectData> findById(UUID hospitalId, UUID id) {
        var row = store.projects.get(id);
        return row != null && row.hospitalId.equals(hospitalId) ? Optional.of(data(row)) : Optional.empty();
    }

    @Override
    public Optional<ProjectData> findByKey(UUID hospitalId, String projectKey) {
        return store.projects.values().stream()
                .filter(row -> row.hospitalId.equals(hospitalId)
                        && row.projectKey.equals(projectKey))
                .findFirst()
                .map(this::data);
    }

    @Override
    public List<ProjectData> findAll(UUID hospitalId) {
        return store.projects.values().stream().filter(row -> row.hospitalId.equals(hospitalId))
                .map(this::data).toList();
    }

    @Override
    public Optional<ProjectData> findVisibleByKey(
            UUID hospitalId, String projectKey, UUID userId, boolean hospitalAdmin) {
        return store.projects.values().stream()
                .filter(row -> row.hospitalId.equals(hospitalId))
                .filter(row -> row.projectKey.equals(projectKey))
                .filter(row -> hospitalAdmin || isMember(hospitalId, row.id, userId))
                .findFirst()
                .map(this::data);
    }

    @Override
    public List<ProjectData> findVisible(
            UUID hospitalId, UUID userId, boolean hospitalAdmin) {
        return store.projects.values().stream()
                .filter(row -> row.hospitalId.equals(hospitalId))
                .filter(row -> hospitalAdmin || isMember(hospitalId, row.id, userId))
                .sorted(Comparator.comparing(row -> row.code))
                .map(this::data)
                .toList();
    }

    @Override
    public Optional<UUID> findIdempotentResource(UUID hospitalId, UUID userId, String operation, String key) {
        return Optional.ofNullable(store.idempotency.get(scope(hospitalId, userId, operation, key)));
    }

    @Override
    public ProjectData insert(UUID hospitalId, String projectKey, String code, String name) {
        boolean duplicate = store.projects.values().stream()
                .anyMatch(row -> row.hospitalId.equals(hospitalId) && row.code.equalsIgnoreCase(code));
        if (duplicate) throw BusinessException.conflict("本院课题编码已存在");
        boolean duplicateKey = store.projects.values().stream()
                .anyMatch(row -> row.projectKey.equals(projectKey));
        if (duplicateKey) throw BusinessException.conflict("课题公开标识冲突");
        var row = new PlatformStore.ProjectRow(
                UUID.randomUUID(), hospitalId, projectKey, code, name);
        store.projects.put(row.id, row);
        return data(row);
    }

    @Override
    public void saveIdempotency(UUID hospitalId, UUID userId, String operation, String key, UUID resourceId) {
        store.idempotency.putIfAbsent(scope(hospitalId, userId, operation, key), resourceId);
    }

    @Override
    public ProjectData update(UUID hospitalId, UUID id, String name, long expectedVersion) {
        var row = store.projects.get(id);
        if (row == null || !row.hospitalId.equals(hospitalId)) {
            throw BusinessException.projectNotFound();
        }
        synchronized (row) {
            if (row.version != expectedVersion) throw BusinessException.conflict("课题已被其他用户修改");
            row.name = name;
            row.version++;
        }
        return data(row);
    }

    private String scope(UUID hospitalId, UUID userId, String operation, String key) {
        return hospitalId + ":" + userId + ":" + operation + ":" + key;
    }

    private boolean isMember(UUID hospitalId, UUID projectId, UUID userId) {
        return store.projectMembers.values().stream()
                .anyMatch(member -> member.hospitalId().equals(hospitalId)
                        && member.projectId().equals(projectId)
                        && member.userId().equals(userId));
    }

    private ProjectData data(PlatformStore.ProjectRow row) {
        return new ProjectData(
                row.id, row.hospitalId, row.projectKey, row.code, row.name, row.version);
    }
}
