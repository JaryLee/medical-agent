package com.jarylee.medicalagent.workflow;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AgentEventStream {
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers =
            new ConcurrentHashMap<>();
    private Duration timeout = Duration.ofMinutes(30);

    @Value("${medical.agent.sse-timeout:30m}")
    void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public SseEmitter subscribe(UUID taskId, List<AgentWorkflowRepository.EventData> replay) {
        var emitter = new SseEmitter(timeout.toMillis());
        var taskSubscribers = subscribers.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>());
        taskSubscribers.add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));
        try {
            for (var event : replay) send(emitter, event);
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publish(AgentWorkflowRepository.EventData event) {
        for (var emitter : subscribers.getOrDefault(event.taskId(), new CopyOnWriteArrayList<>())) {
            try {
                send(emitter, event);
            } catch (IOException exception) {
                remove(event.taskId(), emitter);
                emitter.completeWithError(exception);
            }
        }
    }

    private void send(SseEmitter emitter, AgentWorkflowRepository.EventData event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.id()))
                .name(event.eventType())
                .data(event));
    }

    private void remove(UUID taskId, SseEmitter emitter) {
        var taskSubscribers = subscribers.get(taskId);
        if (taskSubscribers == null) return;
        taskSubscribers.remove(emitter);
        if (taskSubscribers.isEmpty()) subscribers.remove(taskId, taskSubscribers);
    }
}
