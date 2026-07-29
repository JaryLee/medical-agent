package com.jarylee.medicalagent.research;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository {
    void add(UUID hospitalId, UUID projectId, UUID userId, ProjectMemberRole role);
    List<MemberData> findAll(UUID hospitalId, UUID projectId);
    Optional<ProjectMemberRole> findRole(UUID hospitalId, UUID projectId, UUID userId);

    record MemberData(UUID projectId, UUID userId, ProjectMemberRole role) {}
}
