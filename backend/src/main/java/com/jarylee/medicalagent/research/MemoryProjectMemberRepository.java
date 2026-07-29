package com.jarylee.medicalagent.research;

import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("memory")
public class MemoryProjectMemberRepository implements ProjectMemberRepository {
    private final PlatformStore store;

    public MemoryProjectMemberRepository(PlatformStore store) {
        this.store = store;
    }

    @Override
    public void add(UUID hospitalId, UUID projectId, UUID userId, ProjectMemberRole role) {
        var previous = store.projectMembers.putIfAbsent(key(projectId, userId),
                new PlatformStore.ProjectMemberRow(hospitalId, projectId, userId, role.name()));
        if (previous != null) throw BusinessException.conflict("用户已是课题成员");
    }

    @Override
    public List<MemberData> findAll(UUID hospitalId, UUID projectId) {
        return store.projectMembers.values().stream()
                .filter(row -> row.hospitalId().equals(hospitalId) && row.projectId().equals(projectId))
                .map(row -> new MemberData(projectId, row.userId(), ProjectMemberRole.valueOf(row.role())))
                .toList();
    }

    @Override
    public Optional<ProjectMemberRole> findRole(UUID hospitalId, UUID projectId, UUID userId) {
        var row = store.projectMembers.get(key(projectId, userId));
        return row != null && row.hospitalId().equals(hospitalId)
                ? Optional.of(ProjectMemberRole.valueOf(row.role())) : Optional.empty();
    }

    private String key(UUID projectId, UUID userId) {
        return projectId + ":" + userId;
    }
}
