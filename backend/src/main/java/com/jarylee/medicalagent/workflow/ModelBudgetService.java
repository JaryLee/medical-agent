package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.agent.model.ModelRoute;
import com.jarylee.medicalagent.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;

@Service
public class ModelBudgetService {
    private final ProjectModelBudgetRepository budgets;
    private final ModelCallAuditRepository calls;
    private final ModelCostCalculator costs;
    private final Clock clock;
    private final long defaultMaxCallCostMicros;
    private final long defaultMaxProjectCostMicros;
    private final String currency;
    private final int defaultMaxOutputTokens;

    public ModelBudgetService(
            ProjectModelBudgetRepository budgets,
            ModelCallAuditRepository calls,
            ModelCostCalculator costs,
            Clock clock,
            @Value("${medical.model.max-call-cost-micros:250000}")
            long defaultMaxCallCostMicros,
            @Value("${medical.model.max-project-cost-micros:5000000}")
            long defaultMaxProjectCostMicros,
            @Value("${medical.model.budget-currency:USD}")
            String currency,
            @Value("${medical.model.max-output-tokens:4096}")
            int defaultMaxOutputTokens) {
        if (defaultMaxCallCostMicros <= 0
                || defaultMaxProjectCostMicros < defaultMaxCallCostMicros
                || defaultMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("默认模型预算配置不合法");
        }
        this.budgets = budgets;
        this.calls = calls;
        this.costs = costs;
        this.clock = clock;
        this.defaultMaxCallCostMicros = defaultMaxCallCostMicros;
        this.defaultMaxProjectCostMicros = defaultMaxProjectCostMicros;
        this.currency = currency.strip().toUpperCase(Locale.ROOT);
        this.defaultMaxOutputTokens = defaultMaxOutputTokens;
    }

    @Transactional
    public synchronized void reserveAndStart(
            AgentWorkflowRepository.TaskData task,
            ModelRoute route,
            int controlledInputCharacters,
            int promptCharacters,
            ModelCallAuditRepository.ModelCallData draft) {
        boolean synthetic = "mock".equalsIgnoreCase(
                route.model().provider());
        long reservation;
        if (synthetic) {
            reservation = 0;
        } else {
            if (!route.pricing().priced()) {
                throw BusinessException.conflict(
                        "MODEL_PRICE_REQUIRED",
                        "真实模型路由缺少版本化价格，已阻止调用");
            }
            long estimatedInputTokens = Math.max(
                    1L,
                    (Math.addExact(
                            controlledInputCharacters,
                            promptCharacters) + 1L) / 2L);
            reservation = costs.estimateMaximum(
                    route.pricing(),
                    estimatedInputTokens,
                    defaultMaxOutputTokens);
        }
        var budget = budgets.lockOrCreate(
                task.hospitalId(),
                task.projectId(),
                task.createdBy(),
                currency,
                defaultMaxCallCostMicros,
                defaultMaxProjectCostMicros,
                clock.instant());
        if (!"ACTIVE".equals(budget.status())) {
            throw BusinessException.conflict(
                    "MODEL_BUDGET_DISABLED",
                    "当前课题已停用模型调用");
        }
        if (reservation > budget.maxCallCostMicros()) {
            throw BusinessException.conflict(
                    "MODEL_CALL_BUDGET_EXCEEDED",
                    "本次模型调用的最坏成本估算超过单次预算");
        }
        var current = calls.projectConsumption(
                task.hospitalId(), task.projectId());
        long after = Math.addExact(
                current.committedOrReservedCostMicros(), reservation);
        if (after > budget.maxProjectCostMicros()) {
            throw BusinessException.conflict(
                    "MODEL_PROJECT_BUDGET_EXCEEDED",
                    "课题模型预算不足，已在发起外部调用前阻止");
        }
        calls.start(withReservation(draft, reservation));
    }

    @Transactional
    public synchronized void verifyAfterCompletion(
            AgentWorkflowRepository.TaskData task,
            ModelRoute route,
            ModelCostCalculator.CostResult actualCost) {
        if ("mock".equalsIgnoreCase(route.model().provider())) return;
        var budget = budgets.lockOrCreate(
                task.hospitalId(),
                task.projectId(),
                task.createdBy(),
                currency,
                defaultMaxCallCostMicros,
                defaultMaxProjectCostMicros,
                clock.instant());
        var consumption = calls.projectConsumption(
                task.hospitalId(), task.projectId());
        boolean unverifiable = actualCost == null
                || !"ESTIMATED".equals(actualCost.status())
                || actualCost.estimatedCostMicros() == null;
        boolean perCallExceeded = !unverifiable
                && actualCost.estimatedCostMicros()
                > budget.maxCallCostMicros();
        boolean projectExceeded =
                consumption.committedOrReservedCostMicros()
                        > budget.maxProjectCostMicros();
        if (unverifiable || perCallExceeded || projectExceeded) {
            if (!"DISABLED".equals(budget.status())) {
                budgets.update(
                        task.hospitalId(),
                        task.projectId(),
                        budget.version(),
                        budget.maxCallCostMicros(),
                        budget.maxProjectCostMicros(),
                        "DISABLED",
                        clock.instant());
            }
            throw BusinessException.conflict(
                    "MODEL_COST_VERIFICATION_FAILED",
                    "Provider 用量无法核验或实际成本超过预算，已停用本课题后续模型调用");
        }
    }

    private ModelCallAuditRepository.ModelCallData withReservation(
            ModelCallAuditRepository.ModelCallData value,
            long reservation) {
        return new ModelCallAuditRepository.ModelCallData(
                value.id(), value.hospitalId(), value.projectId(),
                value.taskId(), value.stepCode(), value.attemptNo(),
                value.provider(), value.modelName(), value.promptVersion(),
                value.inputSchemaVersion(), value.outputSchemaVersion(),
                value.inputSha256(), value.outputSha256(),
                value.inputSnapshotJson(), value.outputSnapshotJson(),
                value.rawPayloadObjectKey(), value.payloadPurgedAt(),
                value.status(), value.errorCode(), value.errorMessage(),
                value.startedAt(), value.completedAt(),
                value.payloadRetentionUntil(),
                value.metadataRetentionUntil(),
                value.logicalModelType(), value.routePolicyVersion(),
                value.routeReason(), value.providerRequestId(),
                value.usageSource(), value.inputTokens(),
                value.cachedInputTokens(), value.outputTokens(),
                value.totalTokens(), value.priceVersion(),
                value.priceCurrency(), value.estimatedCostMicros(),
                value.costStatus(), reservation);
    }
}
