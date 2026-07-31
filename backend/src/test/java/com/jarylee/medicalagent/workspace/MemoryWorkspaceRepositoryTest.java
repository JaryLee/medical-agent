package com.jarylee.medicalagent.workspace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryWorkspaceRepositoryTest {
    @Test
    void allowsOnlyOneRunningCommandPerProjectVersion() {
        var repository = new MemoryWorkspaceRepository();
        UUID hospitalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-30T08:00:00Z");
        long version = repository.requireCursor(
                hospitalId, projectId, now).readModelVersion();

        WorkspaceRepository.Reservation first = repository.reserve(draft(
                hospitalId, projectId, actorId,
                "workspace-memory-a-0001", version, now));
        WorkspaceRepository.Reservation second = repository.reserve(draft(
                hospitalId, projectId, actorId,
                "workspace-memory-b-0001", version, now));
        WorkspaceRepository.Reservation replay = repository.reserve(draft(
                hospitalId, projectId, actorId,
                "workspace-memory-a-0001", version, now));

        assertThat(first.acquired()).isTrue();
        assertThat(second.versionConflict()).isTrue();
        assertThat(replay.existing()).isNotNull();
        assertThat(replay.existing().status()).isEqualTo("RUNNING");
    }

    private WorkspaceRepository.CommandDraft draft(
            UUID hospitalId,
            UUID projectId,
            UUID actorId,
            String idempotencyKey,
            long version,
            Instant now) {
        return new WorkspaceRepository.CommandDraft(
                UUID.randomUUID(), hospitalId, projectId, actorId,
                "START_RESEARCH_IDEA", idempotencyKey,
                "a".repeat(64), version, now);
    }
}
