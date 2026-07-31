package com.jarylee.medicalagent.workspace;

import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.workflow.ModelCallAuditRepository;
import com.jarylee.medicalagent.workflow.ProjectModelBudgetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WorkspaceModelUsageService {
    private static final Set<String> BUDGET_STATUSES =
            Set.of("ACTIVE", "DISABLED");
    private static final String DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批";

    private final WorkspaceReadModelService readModels;
    private final ModelCallAuditRepository calls;
    private final ProjectModelBudgetRepository budgets;
    private final ModelRouter models;
    private final AuditService audit;
    private final Clock clock;
    private final long defaultMaxCall;
    private final long defaultMaxProject;
    private final String defaultCurrency;
    private final String configuredMode;
    private final boolean externalEnabled;

    public WorkspaceModelUsageService(
            WorkspaceReadModelService readModels,
            ModelCallAuditRepository calls,
            ProjectModelBudgetRepository budgets,
            ModelRouter models,
            AuditService audit,
            Clock clock,
            @Value("${medical.model.max-call-cost-micros:250000}")
            long defaultMaxCall,
            @Value("${medical.model.max-project-cost-micros:5000000}")
            long defaultMaxProject,
            @Value("${medical.model.budget-currency:USD}")
            String defaultCurrency,
            @Value("${medical.model.mode:mock}") String configuredMode,
            @Value("${medical.model.external-enabled:false}")
            boolean externalEnabled) {
        this.readModels = readModels;
        this.calls = calls;
        this.budgets = budgets;
        this.models = models;
        this.audit = audit;
        this.clock = clock;
        this.defaultMaxCall = defaultMaxCall;
        this.defaultMaxProject = defaultMaxProject;
        this.defaultCurrency =
                defaultCurrency.strip().toUpperCase(Locale.ROOT);
        this.configuredMode = configuredMode;
        this.externalEnabled = externalEnabled;
    }

    public WorkspaceModels.Envelope<ModelUsageView> usage(
            String projectKey) {
        var context = readModels.resolve(projectKey);
        var aggregate = readModels.aggregate(context);
        var usage = calls.projectConsumption(
                context.actor().hospitalId(), context.project().id());
        List<ModelCallView> history = calls.findByProject(
                        context.actor().hospitalId(),
                        context.project().id()).stream()
                .map(value -> new ModelCallView(
                        callKey(projectKey, value.id().toString()),
                        logicalTypeLabel(value.logicalModelType()),
                        value.provider(),
                        value.modelName(),
                        value.status(),
                        statusLabel(value.status()),
                        value.usageSource(),
                        value.inputTokens(),
                        value.cachedInputTokens(),
                        value.outputTokens(),
                        value.totalTokens(),
                        value.priceVersion(),
                        value.priceCurrency(),
                        value.reservedCostMicros(),
                        value.estimatedCostMicros(),
                        value.costStatus(),
                        costStatusLabel(value.costStatus()),
                        value.startedAt(),
                        value.completedAt()))
                .toList();
        return new WorkspaceModels.Envelope<>(
                new ModelUsageView(
                        usage.callCount(),
                        usage.succeededCostMicros(),
                        usage.activeReservationCostMicros(),
                        usage.committedOrReservedCostMicros(),
                        history,
                        DISCLAIMER),
                new WorkspaceModels.ResponseMeta(
                        aggregate.cursor().readModelVersion(),
                        clock.instant(),
                        aggregate.cursor().latestEventId()));
    }

    public WorkspaceModels.Envelope<ModelGovernanceView> governance(
            String projectKey) {
        var context = readModels.resolve(projectKey);
        var aggregate = readModels.aggregate(context);
        return new WorkspaceModels.Envelope<>(
                governanceView(context),
                new WorkspaceModels.ResponseMeta(
                        aggregate.cursor().readModelVersion(),
                        clock.instant(),
                        aggregate.cursor().latestEventId()));
    }

    @Transactional
    public WorkspaceModels.Envelope<ModelGovernanceView> updateBudget(
            String projectKey, BudgetUpdateRequest request) {
        var context = readModels.resolve(projectKey);
        if (!context.actor().hasRole(Role.HOSPITAL_ADMIN)) {
            throw BusinessException.forbidden("只有医院管理员可以调整模型预算");
        }
        BudgetUpdateRequest value = validate(request);
        var consumption = calls.projectConsumption(
                context.actor().hospitalId(), context.project().id());
        if (value.maxProjectCostMicros()
                < consumption.committedOrReservedCostMicros()) {
            throw BusinessException.conflict(
                    "MODEL_BUDGET_BELOW_CURRENT_USAGE",
                    "课题总预算不能低于已提交或预留的模型成本");
        }
        var current = budgets.lockOrCreate(
                context.actor().hospitalId(),
                context.project().id(),
                context.actor().userId(),
                defaultCurrency,
                defaultMaxCall,
                defaultMaxProject,
                clock.instant());
        if (current.version() != value.expectedVersion()) {
            throw BusinessException.conflict(
                    "MODEL_BUDGET_VERSION_CONFLICT",
                    "模型预算已变化，请刷新后重试");
        }
        budgets.update(
                context.actor().hospitalId(),
                context.project().id(),
                value.expectedVersion(),
                value.maxCallCostMicros(),
                value.maxProjectCostMicros(),
                value.status(),
                clock.instant());
        audit.record(
                context.actor(),
                "WORKSPACE_MODEL_BUDGET_UPDATED",
                "RESEARCH_PROJECT",
                context.project().projectKey());
        var aggregate = readModels.aggregate(context);
        return new WorkspaceModels.Envelope<>(
                governanceView(context),
                new WorkspaceModels.ResponseMeta(
                        aggregate.cursor().readModelVersion(),
                        clock.instant(),
                        aggregate.cursor().latestEventId()));
    }

    private ModelGovernanceView governanceView(
            WorkspaceReadModelService.WorkspaceContext context) {
        var budget = budgets.find(
                        context.actor().hospitalId(), context.project().id())
                .orElse(null);
        var usage = calls.projectConsumption(
                context.actor().hospitalId(), context.project().id());
        List<RouteView> routes = java.util.Arrays.stream(
                        LogicalModelType.values())
                .map(models::resolve)
                .map(value -> new RouteView(
                        logicalTypeLabel(value.logicalModelType().name()),
                        value.model().provider(),
                        value.model().modelName(),
                        value.policyVersion(),
                        value.routeReason(),
                        value.pricing().priced(),
                        value.pricing().version(),
                        value.pricing().currency()))
                .toList();
        return new ModelGovernanceView(
                configuredMode,
                externalEnabled,
                !externalEnabled,
                routes,
                new BudgetView(
                        budget == null ? defaultCurrency : budget.currency(),
                        budget == null ? defaultMaxCall
                                : budget.maxCallCostMicros(),
                        budget == null ? defaultMaxProject
                                : budget.maxProjectCostMicros(),
                        budget == null ? "ACTIVE" : budget.status(),
                        budget == null ? 0 : budget.version(),
                        budget != null,
                        usage.committedOrReservedCostMicros(),
                        usage.activeReservationCostMicros(),
                        Math.max(
                                0L,
                                (budget == null ? defaultMaxProject
                                        : budget.maxProjectCostMicros())
                                        - usage.committedOrReservedCostMicros())),
                "预算在外部调用前按最坏 Token 上限原子预留；真实路由缺少版本化价格时会阻止调用。",
                DISCLAIMER);
    }

    private BudgetUpdateRequest validate(BudgetUpdateRequest value) {
        if (value == null
                || value.expectedVersion() < 0
                || value.maxCallCostMicros() <= 0
                || value.maxProjectCostMicros()
                < value.maxCallCostMicros()
                || !BUDGET_STATUSES.contains(value.status())) {
            throw new IllegalArgumentException("模型预算参数不合法");
        }
        return value;
    }

    private String callKey(String projectKey, String callId) {
        return "mcall_" + sha256(projectKey + ":" + callId)
                .substring(0, 26).toUpperCase(Locale.ROOT);
    }

    private String logicalTypeLabel(String value) {
        return switch (value) {
            case "RESEARCH_FAST" -> "快速科研分析";
            case "RESEARCH_STANDARD" -> "标准科研生成";
            case "RESEARCH_REASONING" -> "科研设计推理";
            case "RESEARCH_REVIEW" -> "独立辅助复核";
            default -> "未知模型类型";
        };
    }

    private String statusLabel(String value) {
        return switch (value) {
            case "REQUESTED" -> "调用中";
            case "SUCCEEDED" -> "调用成功";
            case "FAILED" -> "调用失败";
            default -> "未知状态";
        };
    }

    private String costStatusLabel(String value) {
        return switch (value) {
            case "ESTIMATED" -> "按 Provider Token 用量估算";
            case "TEST_ONLY" -> "合成测试调用，不计真实费用";
            case "USAGE_UNAVAILABLE" -> "Provider 未返回 Token 用量";
            case "UNPRICED" -> "未配置价格";
            default -> "未知成本状态";
        };
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("模型调用公开标识生成失败", exception);
        }
    }

    public record BudgetUpdateRequest(
            long expectedVersion,
            long maxCallCostMicros,
            long maxProjectCostMicros,
            String status) {}

    public record ModelUsageView(
            long callCount,
            long succeededCostMicros,
            long activeReservationCostMicros,
            long committedOrReservedCostMicros,
            List<ModelCallView> calls,
            String disclaimer) {}

    public record ModelCallView(
            String callKey,
            String logicalModelTypeLabel,
            String provider,
            String modelName,
            String status,
            String statusLabel,
            String usageSource,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long totalTokens,
            String priceVersion,
            String priceCurrency,
            Long reservedCostMicros,
            Long estimatedCostMicros,
            String costStatus,
            String costStatusLabel,
            Instant startedAt,
            Instant completedAt) {}

    public record ModelGovernanceView(
            String configuredMode,
            boolean externalModelEnabled,
            boolean externalModelOffByDefault,
            List<RouteView> routes,
            BudgetView budget,
            String budgetPolicy,
            String disclaimer) {}

    public record RouteView(
            String logicalModelTypeLabel,
            String provider,
            String modelName,
            String policyVersion,
            String routeReason,
            boolean priced,
            String priceVersion,
            String priceCurrency) {}

    public record BudgetView(
            String currency,
            long maxCallCostMicros,
            long maxProjectCostMicros,
            String status,
            long version,
            boolean persisted,
            long committedOrReservedCostMicros,
            long activeReservationCostMicros,
            long remainingCostMicros) {}
}
