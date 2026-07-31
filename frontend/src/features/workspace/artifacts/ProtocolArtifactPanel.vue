<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import type {
  ArtifactSectionView,
  ProtocolModelCandidate,
  ProtocolModelReview,
} from "../../../types/workspace";
import { useWorkspaceV2Store } from "../../../stores/workspaceV2";
import {
  getProtocolModelCandidates,
  getProtocolModelReviews,
} from "../../../api/workspaceV2";
import { asRecord, asRecords, asStrings, count, text } from "../artifactData";

const props = defineProps<{
  projectKey: string;
  data: ArtifactSectionView;
}>();
const emit = defineEmits<{
  dirtyChange: [dirty: boolean];
}>();

const workspace = useWorkspaceV2Store();
const content = computed(() => asRecord(props.data.content));
const protocol = computed(() => asRecord(content.value.protocol));
const sectionDrafts = ref<Record<string, string>>({});
const sectionReasons = ref<Record<string, string>>({});
const comparisonLeft = ref<Record<string, string>>({});
const comparisonRight = ref<Record<string, string>>({});
const modelCandidates = ref<ProtocolModelCandidate[]>([]);
const modelReviews = ref<ProtocolModelReview[]>([]);
const modelRecordsLoading = ref(false);

watch(
  protocol,
  (value) => {
    const sections = asRecords(value.sections);
    sectionDrafts.value = Object.fromEntries(
      sections.map((item) => [text(item.sectionKey), text(item.content)]),
    );
    sectionReasons.value = {};
    comparisonLeft.value = Object.fromEntries(
      sections.map((item) => {
        const history = asRecords(item.versionHistory);
        return [
          text(item.sectionKey),
          text(history.at(-2)?.historyKey ?? history[0]?.historyKey),
        ];
      }),
    );
    comparisonRight.value = Object.fromEntries(
      sections.map((item) => {
        const history = asRecords(item.versionHistory);
        return [text(item.sectionKey), text(history.at(-1)?.historyKey)];
      }),
    );
  },
  { immediate: true },
);

const protocolDirty = computed(() =>
  asRecords(protocol.value.sections).some(
    (item) => sectionDrafts.value[text(item.sectionKey)] !== text(item.content),
  ),
);

watch(protocolDirty, (dirty) => emit("dirtyChange", dirty), {
  immediate: true,
});

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

function originLabel(value: unknown) {
  return (
    {
      AGENT_DETERMINISTIC: "系统确定性初稿",
      AGENT_MODEL: "模型辅助草稿",
      HUMAN: "人工修订",
    }[text(value)] ?? "来源待确认"
  );
}

function historyContent(item: Record<string, unknown>, historyKey: string) {
  return text(
    asRecords(item.versionHistory).find(
      (version) => text(version.historyKey) === historyKey,
    )?.content,
    "请选择一个版本",
  );
}

function reviewFor(candidateKey: string) {
  return modelReviews.value.find(
    (review) => review.candidateKey === candidateKey,
  );
}

function candidateSectionTitle(sectionKey: string) {
  return text(
    asRecords(protocol.value.sections).find(
      (section) => text(section.sectionKey) === sectionKey,
    )?.title,
    "方案章节",
  );
}

function reviewSeverityLabel(value: string) {
  return (
    {
      NONE: "未发现问题",
      LOW: "低风险建议",
      MEDIUM: "中等风险建议",
      HIGH: "高风险建议",
      BLOCKING: "存在阻断问题",
    }[value] ?? "待复核"
  );
}

async function loadModelRecords() {
  modelRecordsLoading.value = true;
  try {
    const [candidates, reviews] = await Promise.all([
      getProtocolModelCandidates(props.projectKey),
      getProtocolModelReviews(props.projectKey),
    ]);
    modelCandidates.value = candidates.data;
    modelReviews.value = reviews.data;
  } finally {
    modelRecordsLoading.value = false;
  }
}

async function generateModelCandidate(item: Record<string, unknown>) {
  const succeeded = await workspace.runAction(
    props.projectKey,
    "GENERATE_PROTOCOL_SECTION_CANDIDATE",
    { sectionKey: text(item.sectionKey) },
    "protocol",
  );
  if (succeeded) await loadModelRecords();
}

async function reviewModelCandidate(candidate: ProtocolModelCandidate) {
  const succeeded = await workspace.runAction(
    props.projectKey,
    "REVIEW_PROTOCOL_SECTION_CANDIDATE",
    { candidateKey: candidate.candidateKey },
    "protocol",
  );
  if (succeeded) await loadModelRecords();
}

async function applyModelCandidate(candidate: ProtocolModelCandidate) {
  const succeeded = await workspace.runAction(
    props.projectKey,
    "APPLY_PROTOCOL_SECTION_CANDIDATE",
    {
      candidateKey: candidate.candidateKey,
      expectedCandidateVersion: candidate.version,
    },
    "protocol",
  );
  if (succeeded) await loadModelRecords();
}

async function updateSection(item: Record<string, unknown>) {
  const sectionKey = text(item.sectionKey);
  const contentValue = sectionDrafts.value[sectionKey]?.trim() ?? "";
  if (!sectionKey || !contentValue) return;
  await workspace.runAction(
    props.projectKey,
    "UPDATE_PROTOCOL_SECTION",
    {
      sectionKey,
      expectedSectionVersion: count(item.versionNo),
      content: contentValue,
      changeReason: sectionReasons.value[sectionKey]?.trim() || undefined,
    },
    "protocol",
  );
}

async function regenerateSection(item: Record<string, unknown>) {
  const sectionKey = text(item.sectionKey);
  if (!sectionKey) return;
  await workspace.runAction(
    props.projectKey,
    "REGENERATE_PROTOCOL_SECTION",
    {
      sectionKey,
      expectedSectionVersion: count(item.versionNo),
      changeReason: sectionReasons.value[sectionKey]?.trim() || undefined,
    },
    "protocol",
  );
}

async function submitRevision() {
  await workspace.runAction(
    props.projectKey,
    "SUBMIT_PROTOCOL_REVISION",
    {},
    "protocol",
  );
}

onMounted(() => void loadModelRecords());
</script>

<template>
  <section
    v-if="Object.keys(protocol).length"
    class="v2-section-card"
    aria-labelledby="protocol-title"
  >
    <h3 id="protocol-title">
      {{ text(protocol.title, "研究方案草案") }}
    </h3>
    <p>{{ studyTypeLabel(protocol.studyType) }}</p>
    <section
      v-if="modelCandidates.length"
      class="v2-form-grid"
      aria-labelledby="protocol-model-candidates-title"
    >
      <h4 id="protocol-model-candidates-title">
        单章模型候选与独立辅助复核
      </h4>
      <p class="v2-muted">
        候选不会自动覆盖当前方案。只有通过边界校验、由不同模型完成辅助复核且无阻断问题后，
        才能由您明确采纳为新的章节版本。
      </p>
      <article
        v-for="candidate in modelCandidates"
        :key="candidate.candidateKey"
        class="v2-result-card"
      >
        <strong>
          {{ candidateSectionTitle(candidate.sectionKey) }} · 基于版本
          {{ candidate.baseVersionNo }} ·
          {{ candidate.status === "APPLIED" ? "已采纳" : "待处理" }}
        </strong>
        <div class="v2-protocol-content">
          {{ candidate.content }}
        </div>
        <p v-if="candidate.issuesToConfirm.length">
          待人工确认：{{ candidate.issuesToConfirm.join("；") }}
        </p>
        <template v-if="reviewFor(candidate.candidateKey)">
          <p>
            独立模型复核：
            {{
              reviewSeverityLabel(
                reviewFor(candidate.candidateKey)?.severity ?? "",
              )
            }}
          </p>
          <p>{{ reviewFor(candidate.candidateKey)?.summary }}</p>
        </template>
        <div class="v2-button-row">
          <el-button
            v-if="
              candidate.status === 'VALIDATED'
                && !reviewFor(candidate.candidateKey)
                && actionAllowed('REVIEW_PROTOCOL_SECTION_CANDIDATE')
            "
            :loading="workspace.actionPending || modelRecordsLoading"
            @click="reviewModelCandidate(candidate)"
          >
            使用不同模型辅助复核
          </el-button>
          <el-button
            v-if="
              candidate.status === 'VALIDATED'
                && reviewFor(candidate.candidateKey)
                && reviewFor(candidate.candidateKey)?.severity !== 'BLOCKING'
                && actionAllowed('APPLY_PROTOCOL_SECTION_CANDIDATE')
            "
            type="primary"
            :loading="workspace.actionPending || modelRecordsLoading"
            @click="applyModelCandidate(candidate)"
          >
            明确采纳为新版本
          </el-button>
        </div>
      </article>
    </section>
    <el-collapse>
      <el-collapse-item
        v-for="item in asRecords(protocol.sections)"
        :key="text(item.sectionKey)"
        :name="text(item.sectionKey)"
        :title="`${count(item.sortOrder)}. ${text(item.title)} · v${count(item.versionNo)}`"
      >
        <template v-if="actionAllowed('UPDATE_PROTOCOL_SECTION')">
          <label class="v2-field">
            <span>章节内容</span>
            <el-input
              v-model="sectionDrafts[text(item.sectionKey)]"
              type="textarea"
              :rows="10"
              maxlength="30000"
              show-word-limit
            />
          </label>
          <label class="v2-field">
            <span>变更原因</span>
            <el-input
              v-model="sectionReasons[text(item.sectionKey)]"
              maxlength="80"
              placeholder="请简述本次修订依据"
            />
          </label>
          <div class="v2-button-row">
            <el-button
              type="primary"
              :loading="workspace.actionPending"
              :disabled="
                sectionDrafts[text(item.sectionKey)]?.trim() ===
                  text(item.content).trim()
              "
              @click="updateSection(item)"
            >
              保存为新版本
            </el-button>
            <el-button
              v-if="actionAllowed('REGENERATE_PROTOCOL_SECTION')"
              :loading="workspace.actionPending"
              @click="regenerateSection(item)"
            >
              恢复确定性初稿
            </el-button>
            <el-button
              v-if="actionAllowed('GENERATE_PROTOCOL_SECTION_CANDIDATE')"
              :loading="workspace.actionPending || modelRecordsLoading"
              @click="generateModelCandidate(item)"
            >
              生成本章模型候选
            </el-button>
          </div>
        </template>
        <div
          v-else
          class="v2-protocol-content"
        >
          {{ text(item.content) }}
        </div>
        <p v-if="asStrings(item.issuesToConfirm).length">
          待确认：{{ asStrings(item.issuesToConfirm).join("；") }}
        </p>
        <details v-if="asRecords(item.versionHistory).length">
          <summary>
            查看 {{ asRecords(item.versionHistory).length }} 个历史版本
          </summary>
          <ol class="v2-history-list">
            <li
              v-for="version in asRecords(item.versionHistory)"
              :key="text(version.historyKey)"
            >
              <strong>
                修订 {{ count(version.revisionNo) }} ·
                {{ originLabel(version.origin) }}
              </strong>
              <span>
                {{ text(version.changeReason) }} ·
                {{ text(version.createdAt) }}
              </span>
              <div class="v2-protocol-content">
                {{ text(version.content) }}
              </div>
            </li>
          </ol>
          <div
            v-if="asRecords(item.versionHistory).length > 1"
            class="v2-form-grid"
          >
            <h4>版本内容对比</h4>
            <label class="v2-field">
              <span>基准版本</span>
              <el-select
                v-model="comparisonLeft[text(item.sectionKey)]"
                aria-label="选择基准版本"
              >
                <el-option
                  v-for="version in asRecords(item.versionHistory)"
                  :key="text(version.historyKey)"
                  :label="`修订 ${count(version.revisionNo)}`"
                  :value="text(version.historyKey)"
                />
              </el-select>
            </label>
            <label class="v2-field">
              <span>对比版本</span>
              <el-select
                v-model="comparisonRight[text(item.sectionKey)]"
                aria-label="选择对比版本"
              >
                <el-option
                  v-for="version in asRecords(item.versionHistory)"
                  :key="text(version.historyKey)"
                  :label="`修订 ${count(version.revisionNo)}`"
                  :value="text(version.historyKey)"
                />
              </el-select>
            </label>
            <div class="v2-review-grid">
              <div>
                <strong>基准内容</strong>
                <div class="v2-protocol-content">
                  {{
                    historyContent(item, comparisonLeft[text(item.sectionKey)])
                  }}
                </div>
              </div>
              <div>
                <strong>对比内容</strong>
                <div class="v2-protocol-content">
                  {{
                    historyContent(item, comparisonRight[text(item.sectionKey)])
                  }}
                </div>
              </div>
            </div>
          </div>
        </details>
      </el-collapse-item>
    </el-collapse>
    <el-button
      v-if="actionAllowed('SUBMIT_PROTOCOL_REVISION')"
      type="primary"
      :loading="workspace.actionPending"
      :disabled="protocolDirty"
      @click="submitRevision"
    >
      重新提交并重跑统计与质量检查
    </el-button>
  </section>
</template>
