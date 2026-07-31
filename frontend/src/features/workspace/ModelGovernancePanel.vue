<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  getModelGovernance,
  getModelUsage,
  updateModelBudget,
} from "../../api/workspaceV2";
import { useSessionStore } from "../../stores/session";
import type {
  ModelGovernanceView,
  ModelUsageView,
} from "../../types/workspace";

const props = defineProps<{ projectKey: string }>();
const session = useSessionStore();
const governance = ref<ModelGovernanceView>();
const usage = ref<ModelUsageView>();
const loading = ref(false);
const saving = ref(false);
const errorMessage = ref("");
const maxCall = ref(0);
const maxProject = ref(0);
const budgetStatus = ref<"ACTIVE" | "DISABLED">("ACTIVE");

const canManageBudget = computed(() =>
  session.user?.roles.includes("HOSPITAL_ADMIN"),
);

function money(value: number | undefined, currency = "USD") {
  if (value === undefined) return "暂无";
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency,
    maximumFractionDigits: 6,
  }).format(value / 1_000_000);
}

async function load() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const [governanceResponse, usageResponse] = await Promise.all([
      getModelGovernance(props.projectKey),
      getModelUsage(props.projectKey),
    ]);
    governance.value = governanceResponse.data;
    usage.value = usageResponse.data;
    maxCall.value = governance.value.budget.maxCallCostMicros;
    maxProject.value = governance.value.budget.maxProjectCostMicros;
    budgetStatus.value = governance.value.budget.status;
  } catch {
    errorMessage.value = "模型治理信息暂时不可用，请稍后重试。";
  } finally {
    loading.value = false;
  }
}

async function saveBudget() {
  if (!governance.value) return;
  saving.value = true;
  errorMessage.value = "";
  try {
    governance.value = (
      await updateModelBudget(props.projectKey, {
        expectedVersion: governance.value.budget.version,
        maxCallCostMicros: maxCall.value,
        maxProjectCostMicros: maxProject.value,
        status: budgetStatus.value,
      })
    ).data;
    await load();
  } catch {
    errorMessage.value = "预算保存失败，请刷新后核对当前用量和预算版本。";
  } finally {
    saving.value = false;
  }
}

onMounted(() => void load());
</script>

<template>
  <article class="v2-artifact">
    <header class="v2-artifact-heading">
      <div>
        <span class="eyebrow">模型治理</span>
        <h2>路由、用量与预算</h2>
      </div>
      <span class="v2-status-pill">
        {{
          governance?.externalModelEnabled
            ? "外部模型已显式启用"
            : "外部模型默认关闭"
        }}
      </span>
    </header>

    <el-alert
      title="仅供科研设计讨论，未经伦理和科研管理审批"
      type="warning"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
    />
    <div
      v-if="loading"
      class="v2-loading-state"
      role="status"
    >
      正在读取模型治理记录……
    </div>

    <template v-else-if="governance && usage">
      <section class="v2-section-card">
        <h3>调用边界</h3>
        <p>{{ governance.budgetPolicy }}</p>
        <div class="v2-review-grid">
          <div
            v-for="route in governance.routes"
            :key="route.logicalModelTypeLabel"
            class="v2-result-card"
          >
            <strong>{{ route.logicalModelTypeLabel }}</strong>
            <p>{{ route.provider }} / {{ route.modelName }}</p>
            <p>
              {{ route.priced ? "已配置版本化价格" : "测试路由，不计真实费用" }}
            </p>
          </div>
        </div>
      </section>

      <section class="v2-section-card">
        <h3>课题预算</h3>
        <p>
          已提交或预留：
          {{ money(governance.budget.committedOrReservedCostMicros, governance.budget.currency) }}
          ；剩余：
          {{ money(governance.budget.remainingCostMicros, governance.budget.currency) }}
        </p>
        <form
          v-if="canManageBudget"
          class="v2-form-grid"
          @submit.prevent="saveBudget"
        >
          <label class="v2-field">
            <span>单次上限（微币种单位）</span>
            <el-input-number
              v-model="maxCall"
              :min="1"
              :step="1000"
            />
          </label>
          <label class="v2-field">
            <span>课题总上限（微币种单位）</span>
            <el-input-number
              v-model="maxProject"
              :min="maxCall"
              :step="10000"
            />
          </label>
          <label class="v2-field">
            <span>调用状态</span>
            <el-select v-model="budgetStatus">
              <el-option
                label="允许（仍受预算门禁）"
                value="ACTIVE"
              />
              <el-option
                label="停用全部模型调用"
                value="DISABLED"
              />
            </el-select>
          </label>
          <el-button
            native-type="submit"
            type="primary"
            :loading="saving"
          >
            保存预算
          </el-button>
        </form>
      </section>

      <section class="v2-section-card">
        <h3>调用记录（{{ usage.callCount }}）</h3>
        <p v-if="!usage.calls.length">
          当前课题尚无模型调用记录。
        </p>
        <div
          v-for="call in usage.calls"
          :key="call.callKey"
          class="v2-result-card"
        >
          <strong>{{ call.logicalModelTypeLabel }} · {{ call.statusLabel }}</strong>
          <p>{{ call.provider }} / {{ call.modelName }}</p>
          <p>
            Token：
            {{ call.totalTokens ?? "Provider 未返回" }}；
            成本：{{
              call.estimatedCostMicros === undefined
                ? call.costStatusLabel
                : money(call.estimatedCostMicros, call.priceCurrency)
            }}
          </p>
        </div>
      </section>
    </template>
  </article>
</template>
