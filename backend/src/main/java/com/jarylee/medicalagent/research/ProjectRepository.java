package com.jarylee.medicalagent.research;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    Optional<ProjectData> findById(UUID hospitalId, UUID id);
    List<ProjectData> findAll(UUID hospitalId);
    Optional<UUID> findIdempotentResource(UUID hospitalId, UUID userId, String operation, String key);
    ProjectData insert(UUID hospitalId, String code, String name);
    void saveIdempotency(UUID hospitalId, UUID userId, String operation, String key, UUID resourceId);
    ProjectData update(UUID hospitalId, UUID id, String name, long expectedVersion);

    record ProjectData(UUID id, UUID hospitalId, String code, String name, long version) {}
}
