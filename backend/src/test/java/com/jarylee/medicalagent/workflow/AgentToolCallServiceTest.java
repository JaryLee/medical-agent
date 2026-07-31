package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolCallServiceTest {
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-30T08:00:00Z"), ZoneOffset.UTC);
    private final AgentToolCallService service = new AgentToolCallService(
            new MemoryAgentToolCallRepository(), new ObjectMapper(), clock);

    @Test
    void reusesCompletedOperationAcrossStepAttempts() {
        UUID hospitalId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID firstAttempt = UUID.randomUUID();
        var first = new AgentWorkflowRepository.ClaimHandle(
                hospitalId, taskId, "STEP_08_SEARCH_PUBMED",
                UUID.randomUUID(), firstAttempt, 1, "worker-a");
        var retry = new AgentWorkflowRepository.ClaimHandle(
                hospitalId, taskId, "STEP_08_SEARCH_PUBMED",
                UUID.randomUUID(), UUID.randomUUID(), 2, "worker-b");
        AtomicInteger invocations = new AtomicInteger();

        String initial = service.invoke(
                first, "NCBI_EUTILS_SEARCH", Map.of("query", "anonymous"),
                String.class, () -> "result-" + invocations.incrementAndGet());
        String reused = service.invoke(
                retry, "NCBI_EUTILS_SEARCH", Map.of("query", "anonymous"),
                String.class, () -> "result-" + invocations.incrementAndGet());

        assertThat(initial).isEqualTo("result-1");
        assertThat(reused).isEqualTo("result-1");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void rejectsConcurrentDuplicateOperationAcrossStepAttempts() {
        UUID hospitalId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var repository = new MemoryAgentToolCallRepository();
        Instant startedAt = clock.instant();
        var first = new AgentToolCallRepository.ToolCallData(
                UUID.randomUUID(), hospitalId, taskId, UUID.randomUUID(),
                "STEP_08_SEARCH_PUBMED", 1, "NCBI_EUTILS_SEARCH",
                "operation-key", "request-hash", "RUNNING",
                null, null, null, startedAt, null);
        var reclaimed = new AgentToolCallRepository.ToolCallData(
                UUID.randomUUID(), hospitalId, taskId, UUID.randomUUID(),
                "STEP_08_SEARCH_PUBMED", 2, "NCBI_EUTILS_SEARCH",
                "operation-key", "request-hash", "RUNNING",
                null, null, null, startedAt, null);

        assertThat(repository.begin(first).acquired()).isTrue();
        assertThatThrownBy(() -> repository.begin(reclaimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝重复调用");
    }
}
