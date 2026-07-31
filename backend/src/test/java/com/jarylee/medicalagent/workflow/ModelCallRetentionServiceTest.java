package com.jarylee.medicalagent.workflow;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCallRetentionServiceTest {

    @Test
    void purgesDatabaseSnapshotsAndMetadataButFailsClosedForObjectPayloads() {
        Instant now = Instant.parse("2026-07-30T08:00:00Z");
        UUID hospitalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var repository = new MemoryModelCallAuditRepository();
        UUID payloadOnly = UUID.randomUUID();
        UUID metadataExpired = UUID.randomUUID();
        UUID objectBlocked = UUID.randomUUID();

        complete(repository, call(
                payloadOnly, hospitalId, projectId, taskId, null,
                now.minusSeconds(1), now.plusSeconds(60)), now);
        complete(repository, call(
                metadataExpired, hospitalId, projectId, taskId, null,
                now.minusSeconds(1), now.minusSeconds(1)), now);
        complete(repository, call(
                objectBlocked, hospitalId, projectId, taskId,
                "private/model-call/" + objectBlocked,
                now.minusSeconds(1), now.minusSeconds(1)), now);

        var result = new ModelCallRetentionService(
                repository, Clock.fixed(now, ZoneOffset.UTC)).sweepNow();

        assertThat(result.payloadsPurged()).isEqualTo(2);
        assertThat(result.metadataPurged()).isEqualTo(1);
        assertThat(result.expiredObjectPayloads()).isEqualTo(1);
        var remaining = repository.findByTask(hospitalId, taskId);
        assertThat(remaining).extracting(ModelCallAuditRepository.ModelCallData::id)
                .containsExactly(payloadOnly, objectBlocked);
        var purged = remaining.getFirst();
        assertThat(purged.inputSnapshotJson()).isNull();
        assertThat(purged.outputSnapshotJson()).isNull();
        assertThat(purged.payloadPurgedAt()).isEqualTo(now);
        assertThat(remaining.get(1).rawPayloadObjectKey()).isNotNull();
        assertThat(remaining.get(1).inputSnapshotJson()).isNotNull();
    }

    private void complete(
            MemoryModelCallAuditRepository repository,
            ModelCallAuditRepository.ModelCallData call,
            Instant now) {
        repository.start(call);
        repository.succeed(
                call.id(), "b".repeat(64), "{\"controlled\":true}", now);
    }

    private ModelCallAuditRepository.ModelCallData call(
            UUID id, UUID hospitalId, UUID projectId, UUID taskId,
            String objectKey, Instant payloadRetention,
            Instant metadataRetention) {
        Instant startedAt = Instant.parse("2026-07-29T08:00:00Z");
        return new ModelCallAuditRepository.ModelCallData(
                id, hospitalId, projectId, taskId, "STEP_01_PARSE_IDEA", 1,
                "test", "test-model", "step01-parse-idea/v1",
                "research-model-input/v1", "research-analysis/v1",
                "a".repeat(64), null, "{\"anonymous\":true}", null,
                objectKey, null, "REQUESTED", null, null, startedAt, null,
                payloadRetention, metadataRetention);
    }
}
