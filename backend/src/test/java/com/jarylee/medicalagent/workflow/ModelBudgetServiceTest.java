package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelInvocation;
import com.jarylee.medicalagent.agent.model.ModelRoute;
import com.jarylee.medicalagent.agent.model.ResearchModel;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.safety.ExternalModelInputGuard;
import com.jarylee.medicalagent.safety.PromptInjectionPolicy;
import com.jarylee.medicalagent.safety.SensitiveContentPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelBudgetServiceTest {
    @Test
    void reservesWorstCaseAtomicallyAndBlocksConcurrentSecondCall()
            throws Exception {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-30T08:00:00Z"), ZoneOffset.UTC);
        var calls = new MemoryModelCallAuditRepository();
        var budgets = new MemoryProjectModelBudgetRepository();
        var costs = new ModelCostCalculator();
        var budget = new ModelBudgetService(
                budgets, calls, costs, clock,
                5_000, 5_000, "USD", 4_096);
        var audit = new ModelCallAuditService(
                calls,
                new ObjectMapper().findAndRegisterModules(),
                clock,
                new ExternalModelInputGuard(
                        new SensitiveContentPolicy(),
                        new PromptInjectionPolicy()),
                costs,
                budget);
        var task = new AgentWorkflowRepository.TaskData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "TEST", "RUNNING", "{}", "{}",
                null, clock.instant().plusSeconds(60), false, 0,
                null, null, clock.instant(), clock.instant(), null);
        ResearchModel model = new ResearchModel() {
            @Override public AnalysisResult analyzeIdea(String idea) {
                throw new UnsupportedOperationException();
            }
            @Override public String provider() { return "priced-test-provider"; }
            @Override public String modelName() { return "priced-test-model"; }
        };
        var route = new ModelRoute(
                LogicalModelType.RESEARCH_STANDARD,
                model,
                "budget-test/v1",
                "TEST",
                new ModelRoute.Pricing(
                        "price-test/v1", "USD",
                        1_000_000L, 1_000_000L, 1_000_000L));
        var prompt = new VersionedPrompt(
                "BUDGET_TEST", "budget-prompt/v1", "${input}");
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> audit.invokeStructured(
                    task, 1, prompt, route, "in/v1", "out/v1", "x",
                    () -> {
                        providerCalls.incrementAndGet();
                        enteredProvider.countDown();
                        try {
                            if (!releaseProvider.await(
                                    5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test timeout");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        return new ModelInvocation<>(
                                "ok", "req-1", "stop",
                                ModelInvocation.ModelUsage.providerReported(
                                        1, 0, 1, 2));
                    }));
            assertThat(enteredProvider.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> audit.invokeStructured(
                    task, 2, prompt, route, "in/v1", "out/v1", "y",
                    () -> {
                        providerCalls.incrementAndGet();
                        return ModelInvocation.unmetered("should-not-run");
                    }))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("预算不足");
            assertThat(providerCalls).hasValue(1);
            assertThat(calls.projectConsumption(
                    task.hospitalId(), task.projectId())
                    .activeReservationCostMicros()).isGreaterThan(0);

            releaseProvider.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).output())
                    .isEqualTo("ok");
        }
        assertThat(calls.projectConsumption(
                task.hospitalId(), task.projectId())
                .activeReservationCostMicros()).isZero();
    }

    @Test
    void disablesProjectWhenProviderUsageCannotBeVerified() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-30T08:00:00Z"), ZoneOffset.UTC);
        var calls = new MemoryModelCallAuditRepository();
        var budgets = new MemoryProjectModelBudgetRepository();
        var costs = new ModelCostCalculator();
        var budget = new ModelBudgetService(
                budgets, calls, costs, clock,
                10_000, 20_000, "USD", 4_096);
        var audit = new ModelCallAuditService(
                calls,
                new ObjectMapper().findAndRegisterModules(),
                clock,
                new ExternalModelInputGuard(
                        new SensitiveContentPolicy(),
                        new PromptInjectionPolicy()),
                costs,
                budget);
        var task = new AgentWorkflowRepository.TaskData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "TEST", "RUNNING", "{}", "{}",
                null, clock.instant().plusSeconds(60), false, 0,
                null, null, clock.instant(), clock.instant(), null);
        ResearchModel model = new ResearchModel() {
            @Override public AnalysisResult analyzeIdea(String idea) {
                throw new UnsupportedOperationException();
            }
            @Override public String provider() { return "priced-test-provider"; }
            @Override public String modelName() { return "priced-test-model"; }
        };
        var route = new ModelRoute(
                LogicalModelType.RESEARCH_STANDARD,
                model,
                "budget-test/v1",
                "TEST",
                new ModelRoute.Pricing(
                        "price-test/v1", "USD",
                        1_000_000L, 1_000_000L, 1_000_000L));
        var prompt = new VersionedPrompt(
                "BUDGET_TEST", "budget-prompt/v1", "${input}");

        assertThatThrownBy(() -> audit.invokeStructured(
                task, 1, prompt, route, "in/v1", "out/v1", "x",
                () -> ModelInvocation.unmetered("unverifiable")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
        assertThat(budgets.find(task.hospitalId(), task.projectId())
                .orElseThrow().status()).isEqualTo("DISABLED");
        assertThat(calls.findByProject(
                task.hospitalId(), task.projectId()).getFirst().status())
                .isEqualTo("SUCCEEDED");

        assertThatThrownBy(() -> audit.invokeStructured(
                task, 2, prompt, route, "in/v1", "out/v1", "y",
                () -> ModelInvocation.unmetered("must-not-run")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用");
    }
}
