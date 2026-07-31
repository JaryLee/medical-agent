<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import type {
  ArtifactSectionView,
  ObservationalDesignAdvice,
} from "../../../types/workspace";
import { useWorkspaceV2Store } from "../../../stores/workspaceV2";
import { getObservationalDesignAdvice } from "../../../api/workspaceV2";
import {
  asRecord,
  asRecords,
  asStrings,
  count,
  flag,
  text,
} from "../artifactData";

const props = defineProps<{
  projectKey: string;
  data: ArtifactSectionView;
}>();
const workspace = useWorkspaceV2Store();
const content = computed(() => asRecord(props.data.content));
const recommendation = computed(() => asRecord(content.value.recommendation));
const selectedStudyType = ref("");
const primaryOutcome = ref("");
const protocolAuthorized = ref(false);
const modelAdvice = ref<ObservationalDesignAdvice[]>([]);
const adviceLoading = ref(false);

watch(
  recommendation,
  (value) => {
    selectedStudyType.value = text(value.recommendedStudyType);
    primaryOutcome.value = text(value.primaryOutcomeCandidate);
    protocolAuthorized.value = false;
  },
  { immediate: true },
);

function actionAllowed(code: string) {
  return props.data.allowedActions.some(
    (action) => action.code === code && action.enabled,
  );
}

function studyTypeLabel(value: unknown) {
  return (
    {
      CROSS_SECTIONAL: "横断面研究",
      COHORT: "队列研究",
      CASE_CONTROL: "病例对照研究",
    }[text(value)] ?? "待确认"
  );
}

function conflictLabel(value: string) {
  return (
    {
      MODEL_STUDY_TYPE_DIFFERS_FROM_RULE: "模型建议的研究类型与规则结果不一致",
      MODEL_DID_NOT_DECLARE_RULE_ALIGNMENT: "模型未声明遵循规则结果",
      MODEL_OMITTED_RULE_UNRESOLVED_ITEMS: "模型遗漏了规则识别的信息缺口",
      MODEL_OMITTED_REQUIRED_CONFIRMATIONS: "模型遗漏了必须由研究者确认的事项",
      MANDATORY_DISCLAIMER_MISSING: "模型遗漏了强制科研草案声明",
    }[value] ?? "模型建议与规则边界存在冲突"
  );
}

async function loadModelAdvice() {
  adviceLoading.value = true;
  try {
    modelAdvice.value = (
      await getObservationalDesignAdvice(props.projectKey)
    ).data;
  } finally {
    adviceLoading.value = false;
  }
}

async function requestModelAdvice() {
  const succeeded = await workspace.runAction(
    props.projectKey,
    "REQUEST_DESIGN_MODEL_ADVICE",
    {},
    "design",
  );
  if (succeeded) await loadModelAdvice();
}

async function confirmDesign() {
  if (
    !selectedStudyType.value ||
    !primaryOutcome.value.trim() ||
    !protocolAuthorized.value
  )
    return;
  await workspace.runAction(
    props.projectKey,
    "CONFIRM_OBSERVATIONAL_DESIGN",
    {
      studyType: selectedStudyType.value,
      primaryOutcome: primaryOutcome.value.trim(),
      authorizeProtocolGeneration: true,
    },
    "design",
  );
}

onMounted(() => void loadModelAdvice());
</script>

<template>
  <section
    v-if="Object.keys(recommendation).length"
    class="v2-section-card"
    aria-labelledby="design-recommendation-title"
  >
    <h3 id="design-recommendation-title">
      观察性研究设计比较
    </h3>
    <p>
      推荐：{{ studyTypeLabel(recommendation.recommendedStudyType) }}；
      {{
        flag(recommendation.readyForProtocolDraft)
          ? "信息已具备草案条件"
          : "仍有信息缺口"
      }}
    </p>
    <div
      v-for="alternative in asRecords(recommendation.alternatives)"
      :key="text(alternative.studyType)"
      class="v2-result-card"
    >
      <strong>
        {{ count(alternative.rank) }}.
        {{ studyTypeLabel(alternative.studyType) }}
      </strong>
      <p>{{ text(alternative.rationale) }}</p>
      <p v-if="asStrings(alternative.biasRisks).length">
        主要偏倚：{{ asStrings(alternative.biasRisks).join("；") }}
      </p>
      <p v-if="asStrings(alternative.missingFields).length">
        缺失：{{ asStrings(alternative.missingFields).join("；") }}
      </p>
    </div>
    <section
      v-if="actionAllowed('REQUEST_DESIGN_MODEL_ADVICE')"
      class="v2-result-card"
      aria-labelledby="design-model-advice-title"
    >
      <h4 id="design-model-advice-title">
        模型辅助设计意见
      </h4>
      <p>
        模型只能解释版本化规则结果，不能改变推荐研究类型、确认设计或授权生成方案。
      </p>
      <el-button
        :loading="workspace.actionPending || adviceLoading"
        @click="requestModelAdvice"
      >
        获取只读辅助意见
      </el-button>
    </section>
    <section
      v-for="item in modelAdvice"
      :key="item.adviceKey"
      class="v2-result-card"
    >
      <strong>
        {{ item.status === "ALIGNED" ? "与规则一致" : "发现规则冲突，已阻止采纳" }}
      </strong>
      <p>{{ item.advice.rationale }}</p>
      <p>
        规则推荐：{{ studyTypeLabel(item.ruleRecommendedStudyType) }}；
        模型建议：{{ studyTypeLabel(item.modelSelectedStudyType) }}
      </p>
      <ul v-if="item.conflicts.length">
        <li
          v-for="conflict in item.conflicts"
          :key="conflict"
        >
          {{ conflictLabel(conflict) }}
        </li>
      </ul>
      <p v-if="item.advice.biasConsiderations.length">
        偏倚考虑：{{ item.advice.biasConsiderations.join("；") }}
      </p>
      <p v-if="item.advice.missingFields.length">
        仍缺信息：{{ item.advice.missingFields.join("；") }}
      </p>
      <p class="v2-muted">
        本意见仅作辅助，不替代下方研究者确认。
      </p>
    </section>
    <form
      v-if="actionAllowed('CONFIRM_OBSERVATIONAL_DESIGN')"
      class="v2-form-grid"
      @submit.prevent="confirmDesign"
    >
      <label class="v2-field">
        <span>研究类型</span>
        <el-select v-model="selectedStudyType">
          <el-option
            label="横断面研究"
            value="CROSS_SECTIONAL"
          />
          <el-option
            label="队列研究"
            value="COHORT"
          />
          <el-option
            label="病例对照研究"
            value="CASE_CONTROL"
          />
        </el-select>
      </label>
      <label class="v2-field">
        <span>主要结局</span>
        <el-input
          v-model="primaryOutcome"
          maxlength="1000"
          show-word-limit
        />
      </label>
      <el-checkbox v-model="protocolAuthorized">
        我确认进入科研方案草案生成，不将结果视为正式批准
      </el-checkbox>
      <el-button
        native-type="submit"
        type="primary"
        :loading="workspace.actionPending"
        :disabled="
          !selectedStudyType || !primaryOutcome.trim() || !protocolAuthorized
        "
      >
        确认研究设计
      </el-button>
    </form>
  </section>
</template>
