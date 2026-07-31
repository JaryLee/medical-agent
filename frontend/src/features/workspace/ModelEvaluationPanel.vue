<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  listModelEvaluations,
  startModelEvaluation,
  submitModelEvaluationScore,
} from "../../api/workspaceV2";
import { useSessionStore } from "../../stores/session";
import type { ModelEvaluation } from "../../types/workspace";

const session = useSessionStore();
const evaluations = ref<ModelEvaluation[]>([]);
const loading = ref(false);
const pending = ref(false);
const errorMessage = ref("");
const selectedKey = ref("");
const responsibility = ref<"MEDICAL_REVIEW" | "STATISTICAL_REVIEW">(
  "MEDICAL_REVIEW",
);
const correctnessScore = ref(4);
const completenessScore = ref(4);
const safetyScore = ref(4);
const actionabilityScore = ref(4);
const recommendation = ref<"ACCEPT" | "REVISE" | "REJECT">("REVISE");
const comment = ref("");

const canStart = computed(() =>
  session.user?.roles.includes("HOSPITAL_ADMIN"),
);
const canScore = computed(() =>
  session.user?.roles.includes("EXPERT"),
);
const selected = computed(() =>
  evaluations.value.find(
    (evaluation) => evaluation.evaluationKey === selectedKey.value,
  ),
);

async function load() {
  loading.value = true;
  errorMessage.value = "";
  try {
    evaluations.value = (await listModelEvaluations()).data;
  } catch {
    errorMessage.value = "匿名模型评测记录暂时不可用。";
  } finally {
    loading.value = false;
  }
}

async function startEvaluation() {
  pending.value = true;
  errorMessage.value = "";
  try {
    const result = (await startModelEvaluation()).data;
    await load();
    selectedKey.value = result.evaluationKey;
  } catch {
    errorMessage.value = "启动评测失败，请检查账号权限和模型治理配置。";
  } finally {
    pending.value = false;
  }
}

async function submitScore() {
  if (!selected.value || !comment.value.trim()) return;
  pending.value = true;
  errorMessage.value = "";
  try {
    await submitModelEvaluationScore(selected.value.evaluationKey, {
      responsibility: responsibility.value,
      correctnessScore: correctnessScore.value,
      completenessScore: completenessScore.value,
      safetyScore: safetyScore.value,
      actionabilityScore: actionabilityScore.value,
      recommendation: recommendation.value,
      comment: comment.value.trim(),
    });
    comment.value = "";
    await load();
  } catch {
    errorMessage.value =
      "评分提交失败。两项职责必须由两名不同专家分别提交，且每项只能提交一次。";
  } finally {
    pending.value = false;
  }
}

function recommendationLabel(value: string) {
  return (
    {
      ACCEPT: "接受",
      REVISE: "建议修订",
      REJECT: "拒绝",
    }[value] ?? "待判断"
  );
}

onMounted(() => void load());
</script>

<template>
  <section class="v2-project-shell">
    <header class="v2-project-heading">
      <div>
        <router-link
          class="v2-back-link"
          to="/workspace"
        >
          ← 科研课题工作台
        </router-link>
        <h1>匿名模型评测</h1>
        <p>自动指标与双专家独立评分必须同时完成，不能将模型自评视为验收。</p>
      </div>
      <el-button
        v-if="canStart"
        type="primary"
        :loading="pending"
        @click="startEvaluation"
      >
        启动匿名合成案例评测
      </el-button>
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
      正在读取评测批次……
    </div>
    <div
      v-else
      class="v2-review-grid"
    >
      <section class="v2-section-card">
        <h2>评测批次</h2>
        <p v-if="!evaluations.length">
          尚未创建匿名评测批次。
        </p>
        <button
          v-for="evaluation in evaluations"
          :key="evaluation.evaluationKey"
          type="button"
          class="v2-result-card"
          @click="selectedKey = evaluation.evaluationKey"
        >
          <strong>{{ evaluation.statusLabel }}</strong>
          <span>
            自动指标通过 {{ evaluation.passedCount ?? 0 }} /
            {{ evaluation.caseCount }}；专家评分
            {{ evaluation.expertScores.length }} / 2
          </span>
        </button>
      </section>

      <section
        v-if="selected"
        class="v2-section-card"
      >
        <h2>批次详情</h2>
        <p>{{ selected.statusLabel }}</p>
        <p>数据范围：匿名合成案例，不含真实患者数据。</p>
        <div
          v-for="score in selected.expertScores"
          :key="score.responsibility"
          class="v2-result-card"
        >
          <strong>{{ score.responsibilityLabel }}</strong>
          <p>
            正确性 {{ score.correctnessScore }}；完整性
            {{ score.completenessScore }}；安全性
            {{ score.safetyScore }}；可执行性
            {{ score.actionabilityScore }}
          </p>
          <p>{{ recommendationLabel(score.recommendation) }}：{{ score.comment }}</p>
        </div>

        <form
          v-if="canScore && selected.status === 'WAITING_EXPERT_SCORING'"
          class="v2-form-grid"
          @submit.prevent="submitScore"
        >
          <h3>提交独立专家评分</h3>
          <label class="v2-field">
            <span>评分职责</span>
            <el-select v-model="responsibility">
              <el-option
                label="医学专家评分"
                value="MEDICAL_REVIEW"
              />
              <el-option
                label="统计专家评分"
                value="STATISTICAL_REVIEW"
              />
            </el-select>
          </label>
          <label class="v2-field">
            <span>正确性（1–5）</span>
            <el-input-number
              v-model="correctnessScore"
              :min="1"
              :max="5"
            />
          </label>
          <label class="v2-field">
            <span>完整性（1–5）</span>
            <el-input-number
              v-model="completenessScore"
              :min="1"
              :max="5"
            />
          </label>
          <label class="v2-field">
            <span>安全性（1–5）</span>
            <el-input-number
              v-model="safetyScore"
              :min="1"
              :max="5"
            />
          </label>
          <label class="v2-field">
            <span>可执行性（1–5）</span>
            <el-input-number
              v-model="actionabilityScore"
              :min="1"
              :max="5"
            />
          </label>
          <label class="v2-field">
            <span>建议</span>
            <el-select v-model="recommendation">
              <el-option
                label="接受"
                value="ACCEPT"
              />
              <el-option
                label="建议修订"
                value="REVISE"
              />
              <el-option
                label="拒绝"
                value="REJECT"
              />
            </el-select>
          </label>
          <label class="v2-field">
            <span>评分说明（不得填写真实患者信息）</span>
            <el-input
              v-model="comment"
              type="textarea"
              :rows="4"
              maxlength="2000"
              show-word-limit
            />
          </label>
          <el-button
            native-type="submit"
            type="primary"
            :disabled="!comment.trim()"
            :loading="pending"
          >
            提交不可覆盖的专家评分
          </el-button>
        </form>
      </section>
    </div>
  </section>
</template>
