package com.jarylee.medicalagent.workspace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository {
    Cursor requireCursor(UUID hospitalId, UUID projectId, Instant now);

    List<ProjectEventData> findEventsAfter(
            UUID hospitalId, UUID projectId, long afterEventId, int limit);

    Optional<Long> earliestEventId(UUID hospitalId, UUID projectId);

    Reservation reserve(CommandDraft command);

    void completeCommand(
            UUID hospitalId,
            UUID commandId,
            long resultReadModelVersion,
            String responseJson,
            Instant completedAt);

    void abortCommand(UUID hospitalId, UUID commandId);

    Optional<CommandData> findCommand(
            UUID hospitalId,
            UUID projectId,
            UUID actorUserId,
            String idempotencyKey);

    void recordMemoryChange(UUID hospitalId, UUID projectId, Instant occurredAt);

    record Cursor(
            UUID projectId,
            UUID hospitalId,
            long readModelVersion,
            long latestEventId,
            Instant updatedAt) {}

    record ProjectEventData(
            long id,
            UUID hospitalId,
            UUID projectId,
            long readModelVersion,
            String eventType,
            Instant occurredAt) {}

    record CommandDraft(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID actorUserId,
            String actionCode,
            String idempotencyKey,
            String requestSha256,
            long expectedReadModelVersion,
            Instant createdAt) {}

    record CommandData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID actorUserId,
            String actionCode,
            String idempotencyKey,
            String requestSha256,
            long expectedReadModelVersion,
            Long resultReadModelVersion,
            String status,
            String responseJson,
            Instant createdAt,
            Instant completedAt) {}

    record Reservation(
            boolean acquired,
            CommandData existing,
            boolean versionConflict) {
        public static Reservation newReservation() {
            return new Reservation(true, null, false);
        }

        public static Reservation existingReservation(CommandData value) {
            return new Reservation(false, value, false);
        }

        public static Reservation versionConflictReservation() {
            return new Reservation(false, null, true);
        }
    }
}
