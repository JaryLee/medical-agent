package com.jarylee.medicalagent.research;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    Optional<ProjectData> findById(UUID hospitalId, UUID id);
    Optional<ProjectData> findByKey(UUID hospitalId, String projectKey);
    List<ProjectData> findAll(UUID hospitalId);
    Optional<ProjectData> findVisibleByKey(
            UUID hospitalId, String projectKey, UUID userId, boolean hospitalAdmin);
    List<ProjectData> findVisible(
            UUID hospitalId, UUID userId, boolean hospitalAdmin);
    Optional<UUID> findIdempotentResource(UUID hospitalId, UUID userId, String operation, String key);
    ProjectData insert(UUID hospitalId, String projectKey, String code, String name);
    default ProjectData insert(UUID hospitalId, String code, String name) {
        return insert(hospitalId, ProjectKey.generate(), code, name);
    }
    void saveIdempotency(UUID hospitalId, UUID userId, String operation, String key, UUID resourceId);
    ProjectData update(UUID hospitalId, UUID id, String name, long expectedVersion);

    record ProjectData(
            UUID id,
            UUID hospitalId,
            String projectKey,
            String code,
            String name,
            long version) {}
}
