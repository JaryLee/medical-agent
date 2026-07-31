package com.jarylee.medicalagent.workspace;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("memory")
public class MemoryWorkspaceRepository implements WorkspaceRepository {
    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();
    private final List<ProjectEventData> events = new ArrayList<>();
    private final Map<String, CommandData> commands = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();

    @Override
    public synchronized Cursor requireCursor(UUID hospitalId, UUID projectId, Instant now) {
        return cursors.computeIfAbsent(
                projectScope(hospitalId, projectId),
                ignored -> createInitialCursor(hospitalId, projectId, now));
    }

    @Override
    public synchronized List<ProjectEventData> findEventsAfter(
            UUID hospitalId, UUID projectId, long afterEventId, int limit) {
        return events.stream()
                .filter(event -> event.hospitalId().equals(hospitalId)
                        && event.projectId().equals(projectId)
                        && event.id() > afterEventId)
                .sorted(Comparator.comparingLong(ProjectEventData::id))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized Optional<Long> earliestEventId(UUID hospitalId, UUID projectId) {
        return events.stream()
                .filter(event -> event.hospitalId().equals(hospitalId)
                        && event.projectId().equals(projectId))
                .map(ProjectEventData::id)
                .min(Long::compareTo);
    }

    @Override
    public synchronized Reservation reserve(CommandDraft command) {
        String scope = scope(
                command.hospitalId(), command.projectId(), command.actorUserId(),
                command.idempotencyKey());
        CommandData existing = commands.get(scope);
        if (existing != null) return Reservation.existingReservation(existing);
        boolean projectCommandRunning = commands.values().stream()
                .anyMatch(value ->
                        value.hospitalId().equals(command.hospitalId())
                        && value.projectId().equals(command.projectId())
                        && "RUNNING".equals(value.status()));
        if (projectCommandRunning) {
            return Reservation.versionConflictReservation();
        }
        Cursor cursor = requireCursor(
                command.hospitalId(), command.projectId(), command.createdAt());
        if (cursor.readModelVersion() != command.expectedReadModelVersion()) {
            return Reservation.versionConflictReservation();
        }
        commands.put(scope, new CommandData(
                command.id(), command.hospitalId(), command.projectId(),
                command.actorUserId(), command.actionCode(), command.idempotencyKey(),
                command.requestSha256(), command.expectedReadModelVersion(),
                null, "RUNNING", null, command.createdAt(), null));
        return Reservation.newReservation();
    }

    @Override
    public synchronized void completeCommand(
            UUID hospitalId,
            UUID commandId,
            long resultReadModelVersion,
            String responseJson,
            Instant completedAt) {
        Map.Entry<String, CommandData> entry = commands.entrySet().stream()
                .filter(value -> value.getValue().hospitalId().equals(hospitalId)
                        && value.getValue().id().equals(commandId))
                .findFirst()
                .orElseThrow();
        CommandData value = entry.getValue();
        commands.put(entry.getKey(), new CommandData(
                value.id(), value.hospitalId(), value.projectId(),
                value.actorUserId(), value.actionCode(), value.idempotencyKey(),
                value.requestSha256(), value.expectedReadModelVersion(),
                resultReadModelVersion, "COMPLETED", responseJson,
                value.createdAt(), completedAt));
    }

    @Override
    public synchronized void abortCommand(UUID hospitalId, UUID commandId) {
        commands.entrySet().removeIf(entry ->
                entry.getValue().hospitalId().equals(hospitalId)
                        && entry.getValue().id().equals(commandId)
                        && "RUNNING".equals(entry.getValue().status()));
    }

    @Override
    public Optional<CommandData> findCommand(
            UUID hospitalId,
            UUID projectId,
            UUID actorUserId,
            String idempotencyKey) {
        return Optional.ofNullable(commands.get(scope(
                hospitalId, projectId, actorUserId, idempotencyKey)));
    }

    @Override
    public synchronized void recordMemoryChange(
            UUID hospitalId, UUID projectId, Instant occurredAt) {
        Cursor current = requireCursor(hospitalId, projectId, occurredAt);
        long version = current.readModelVersion() + 1;
        long eventId = eventSequence.incrementAndGet();
        events.add(new ProjectEventData(
                eventId, hospitalId, projectId, version,
                "PROJECT_READ_MODEL_CHANGED", occurredAt));
        cursors.put(projectScope(hospitalId, projectId), new Cursor(
                projectId, hospitalId, version, eventId, occurredAt));
    }

    private Cursor createInitialCursor(UUID hospitalId, UUID projectId, Instant now) {
        long eventId = eventSequence.incrementAndGet();
        events.add(new ProjectEventData(
                eventId, hospitalId, projectId, 1,
                "PROJECT_READ_MODEL_CHANGED", now));
        return new Cursor(projectId, hospitalId, 1, eventId, now);
    }

    private String scope(
            UUID hospitalId,
            UUID projectId,
            UUID actorUserId,
            String idempotencyKey) {
        return hospitalId + ":" + projectId + ":" + actorUserId
                + ":" + idempotencyKey;
    }

    private String projectScope(UUID hospitalId, UUID projectId) {
        return hospitalId + ":" + projectId;
    }
}
