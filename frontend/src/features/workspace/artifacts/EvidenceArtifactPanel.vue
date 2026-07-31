<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { ArtifactSectionView } from "../../../types/workspace";
import { useWorkspaceV2Store } from "../../../stores/workspaceV2";
import { asRecord, asRecords, asStrings, count, text } from "../artifactData";

const props = defineProps<{
  projectKey: string;
  data: ArtifactSectionView;
}>();

const workspace = useWorkspaceV2Store();
const content = computed(() => asRecord(props.data.content));
const searchStrategy = computed(() => asRecord(content.value.searchStrategy));
const pubmed = computed(() => asRecord(content.value.pubmed));
const trials = computed(() => asRecord(content.value.clinicalTrials));
const validation = computed(() => asRecord(content.value.validation));
const similar = computed(() => asRecord(content.value.similarResearch));
const searchQuery = ref("");

watch(
  searchStrategy,
  (value) => {
    searchQuery.value = text(value.pubmedQuery);
  },
  { immediate: true },
);

function actionAllowed(code: string) {
  return props.data.allowedActions.some(
    (action) => action.code === code && action.enabled,
  );
}

function statusLabel(value: unknown) {
  return (
    {
      VERIFIED: "已核验",
      VERIFIED_WITH_METADATA_DIFFERENCES: "核验通过（元数据有差异）",
      MISMATCH: "信息不一致",
      CROSSREF_NOT_FOUND: "Crossref 未找到",
      DOI_NOT_AVAILABLE: "无 DOI",
    }[text(value)] ?? "待处理"
  );
}

function evidenceScopeLabel(value: unknown) {
  return (
    {
      ABSTRACT_ONLY: "摘要级证据",
      REGISTRY_RESULTS_AVAILABLE: "注册记录含结果",
      REGISTRY_RECORD_ONLY: "仅注册记录",
    }[text(value)] ?? "证据范围待确认"
  );
}

function trialStatusLabel(value: unknown) {
  return (
    {
      RECRUITING: "招募中",
      ACTIVE_NOT_RECRUITING: "进行中，已停止招募",
      COMPLETED: "已完成",
      NOT_YET_RECRUITING: "尚未开始招募",
      TERMINATED: "已提前终止",
      WITHDRAWN: "已撤回",
      UNKNOWN: "状态未知",
    }[text(value)] ?? "状态待确认"
  );
}

function pubmedUrl(pmid: unknown) {
  return `https://pubmed.ncbi.nlm.nih.gov/${encodeURIComponent(text(pmid))}/`;
}

function trialUrl(nctId: unknown) {
  return `https://clinicaltrials.gov/study/${encodeURIComponent(text(nctId))}`;
}

async function confirmSearch() {
  if (!searchQuery.value.trim()) return;
  await workspace.runAction(
    props.projectKey,
    "CONFIRM_SEARCH_STRATEGY",
    { pubmedQuery: searchQuery.value.trim() },
    "evidence",
  );
}
</script>

<template>
  <section
    v-if="Object.keys(searchStrategy).length"
    class="v2-section-card"
    aria-labelledby="evidence-strategy-title"
  >
    <h3 id="evidence-strategy-title">
      检索策略
    </h3>
    <p>{{ text(searchStrategy.originalResearchQuestion) }}</p>
    <div class="v2-chip-row">
      <span
        v-for="database in asStrings(searchStrategy.databases)"
        :key="database"
        class="v2-chip"
      >{{ database }}</span>
    </div>
    <label
      v-if="actionAllowed('CONFIRM_SEARCH_STRATEGY')"
      class="v2-field"
    >
      <span>PubMed 检索式</span>
      <el-input
        v-model="searchQuery"
        type="textarea"
        :rows="5"
        maxlength="4000"
        show-word-limit
      />
    </label>
    <pre
      v-else
      class="v2-query"
    >{{ text(searchStrategy.pubmedQuery) }}</pre>
    <el-button
      v-if="actionAllowed('CONFIRM_SEARCH_STRATEGY')"
      type="primary"
      :loading="workspace.actionPending"
      :disabled="!searchQuery.trim()"
      @click="confirmSearch"
    >
      确认并执行检索
    </el-button>
  </section>

  <section
    v-if="Object.keys(pubmed).length"
    class="v2-section-card"
    aria-labelledby="pubmed-results-title"
  >
    <h3 id="pubmed-results-title">
      PubMed 文献
      <small>{{ count(pubmed.returnedCount) }} /
        {{ count(pubmed.totalResultCount) }}</small>
    </h3>
    <div
      v-for="article in asRecords(pubmed.records)"
      :key="text(article.pmid)"
      class="v2-result-card"
    >
      <strong>{{ text(article.title) }}</strong>
      <p>
        <a
          :href="pubmedUrl(article.pmid)"
          target="_blank"
          rel="noopener noreferrer"
        >
          PMID {{ text(article.pmid) }}
        </a>
        · {{ text(article.journal) }}
      </p>
      <p>
        {{ text(article.publicationDate) }} ·
        {{ evidenceScopeLabel(article.evidenceScope) }}
      </p>
    </div>
  </section>

  <section
    v-if="Object.keys(trials).length"
    class="v2-section-card"
    aria-labelledby="trial-results-title"
  >
    <h3 id="trial-results-title">
      注册研究
      <small>{{ count(trials.returnedCount) }} /
        {{ count(trials.totalResultCount) }}</small>
    </h3>
    <div
      v-for="trial in asRecords(trials.records)"
      :key="text(trial.nctId)"
      class="v2-result-card"
    >
      <strong>{{ text(trial.briefTitle) }}</strong>
      <p>
        <a
          :href="trialUrl(trial.nctId)"
          target="_blank"
          rel="noopener noreferrer"
        >
          {{ text(trial.nctId) }}
        </a>
        · {{ trialStatusLabel(trial.overallStatus) }}
      </p>
      <p>注册记录不等于发表证据。</p>
    </div>
  </section>

  <section
    v-if="Object.keys(validation).length"
    class="v2-section-card"
    aria-labelledby="citation-validation-title"
  >
    <h3 id="citation-validation-title">
      引文元数据核验
    </h3>
    <ul class="v2-detail-list">
      <li
        v-for="citation in asRecords(validation.citations)"
        :key="`${text(citation.pmid)}-${text(citation.doi)}`"
      >
        <strong>{{ text(citation.pmid, "无 PMID") }}</strong>
        <span>{{ statusLabel(citation.status) }}</span>
      </li>
    </ul>
  </section>

  <section
    v-if="Object.keys(similar).length"
    class="v2-section-card"
    aria-labelledby="similar-research-title"
  >
    <h3 id="similar-research-title">
      相似研究与潜在空白
    </h3>
    <p>{{ text(similar.conclusion) }}</p>
    <div
      v-for="gap in asRecords(similar.potentialResearchGaps)"
      :key="text(gap.code)"
      class="v2-result-card"
    >
      <strong>{{ text(gap.statement) }}</strong>
      <p>{{ text(gap.basis) }}</p>
    </div>
  </section>
</template>
