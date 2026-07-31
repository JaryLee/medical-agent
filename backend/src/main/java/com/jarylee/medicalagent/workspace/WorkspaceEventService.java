package com.jarylee.medicalagent.workspace;

import com.jarylee.medicalagent.workspace.WorkspaceModels.ProjectEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class WorkspaceEventService {
    private static final int REPLAY_LIMIT = 100;

    private final WorkspaceReadModelService readModels;
    private final WorkspaceRepository repository;
    private final Clock clock;
    private final java.util.concurrent.ScheduledExecutorService poller;
    private Duration timeout = Duration.ofMinutes(30);
    private Duration pollInterval = Duration.ofSeconds(1);

    public WorkspaceEventService(
            WorkspaceReadModelService readModels,
            WorkspaceRepository repository,
            Clock clock) {
        this.readModels = readModels;
        this.repository = repository;
        this.clock = clock;
        this.poller = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "workspace-sse-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Value("${medical.workspace.sse-timeout:30m}")
    void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    @Value("${medical.workspace.sse-poll-interval:1s}")
    void setPollInterval(Duration pollInterval) {
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException(
                    "工作台 SSE 轮询间隔必须大于 0");
        }
        this.pollInterval = pollInterval;
    }

    public SseEmitter subscribe(String projectKey, long afterEventId) {
        if (afterEventId < 0) {
            throw new IllegalArgumentException(
                    "Last-Event-ID 必须为非负整数");
        }
        var context = readModels.resolve(projectKey);
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        AtomicLong cursor = new AtomicLong(afterEventId);
        AtomicInteger polls = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable cleanup = () -> {
            closed.set(true);
            ScheduledFuture<?> scheduled = future.get();
            if (scheduled != null) scheduled.cancel(false);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        Runnable poll = () -> {
            try {
                replay(
                        emitter,
                        context.project().projectKey(),
                        context.actor().hospitalId(),
                        context.project().id(),
                        cursor);
                if (polls.incrementAndGet() % 15 == 0) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                }
            } catch (IOException exception) {
                cleanup.run();
                emitter.completeWithError(exception);
            } catch (RuntimeException exception) {
                cleanup.run();
                emitter.completeWithError(exception);
            }
        };

        poll.run();
        if (!closed.get()) {
            ScheduledFuture<?> scheduled = poller.scheduleAtFixedRate(
                    poll,
                    pollInterval.toMillis(),
                    pollInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
            future.set(scheduled);
            if (closed.get()) {
                scheduled.cancel(false);
            }
        }
        return emitter;
    }

    private void replay(
            SseEmitter emitter,
            String projectKey,
            java.util.UUID hospitalId,
            java.util.UUID projectId,
            AtomicLong cursor) throws IOException {
        WorkspaceRepository.Cursor current = repository.requireCursor(
                hospitalId, projectId, clock.instant());
        Optional<Long> earliest = repository.earliestEventId(
                hospitalId, projectId);
        long after = cursor.get();
        if ((after > current.latestEventId())
                || (after > 0 && earliest.isPresent()
                && after < earliest.get() - 1)) {
            sendResync(emitter, projectKey, current);
            cursor.set(current.latestEventId());
            return;
        }
        List<WorkspaceRepository.ProjectEventData> events =
                repository.findEventsAfter(
                        hospitalId, projectId, after, REPLAY_LIMIT + 1);
        if (events.size() > REPLAY_LIMIT) {
            sendResync(emitter, projectKey, current);
            cursor.set(current.latestEventId());
            return;
        }
        for (var event : events) {
            ProjectEvent publicEvent = new ProjectEvent(
                    event.id(),
                    event.eventType(),
                    projectKey,
                    event.readModelVersion(),
                    event.occurredAt());
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.id()))
                    .name(event.eventType())
                    .data(publicEvent));
            cursor.set(event.id());
        }
    }

    private void sendResync(
            SseEmitter emitter,
            String projectKey,
            WorkspaceRepository.Cursor current) throws IOException {
        ProjectEvent event = new ProjectEvent(
                current.latestEventId(),
                "PROJECT_RESYNC_REQUIRED",
                projectKey,
                current.readModelVersion(),
                current.updatedAt());
        emitter.send(SseEmitter.event()
                .id(Long.toString(current.latestEventId()))
                .name("PROJECT_RESYNC_REQUIRED")
                .data(event));
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
    }
}
