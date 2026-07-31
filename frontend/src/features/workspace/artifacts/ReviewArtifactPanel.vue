<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { ArtifactSectionView } from "../../../types/workspace";
import { useWorkspaceV2Store } from "../../../stores/workspaceV2";
import { asRecord, asRecords, count, flag, text } from "../artifactData";

const props = defineProps<{
  projectKey: string;
  data: ArtifactSectionView;
}>();
const workspace = useWorkspaceV2Store();
const content = computed(() => asRecord(props.data.content));
const review = computed(() => asRecord(content.value.review));
const reviewTargetKey = ref("");
const reviewResponsibility = ref<"MEDICAL_REVIEW" | "STATISTICAL_REVIEW">(
  "MEDICAL_REVIEW",
);
const reviewCommentType = ref("GENERAL");
const reviewComment = ref("");
const reviewDecision = ref<"APPROVE" | "RETURN_FOR_REVISION">("APPROVE");
const reviewSummary = ref("");

watch(
  review,
  (value) => {
    reviewTargetKey.value = text(asRecords(value.commentTargets)[0]?.targetKey);
    reviewComment.value = "";
    reviewSummary.value = "";
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
      APPROVE: "通过",
      RETURN_FOR_REVISION: "退回修订",
    }[text(value)] ?? "待审核"
  );
}

function reviewActionLabel(value: unknown) {
  return (
    {
      REVIEW_OPENED: "发起内部审核",
      COMMENT_ADDED: "添加审核批注",
      MEDICAL_REVIEW_APPROVED: "医学审核通过",
      MEDICAL_REVIEW_RETURNED: "医学审核退回修订",
      STATISTICAL_REVIEW_APPROVED: "统计审核通过",
      STATISTICAL_REVIEW_RETURNED: "统计审核退回修订",
      OWNER_CONFIRMED: "课题负责人确认",
    }[text(value)] ?? "审核记录"
  );
}

function responsibilityLabel(value: unknown) {
  return (
    {
      MEDICAL_REVIEW: "医学审核",
      STATISTICAL_REVIEW: "统计审核",
    }[text(value)] ?? "审核职责"
  );
}

function commentTypeLabel(value: unknown) {
  return (
    {
      MEDICAL: "医学",
      STATISTICAL: "统计",
      REPORTING: "报告规范",
      GENERAL: "一般意见",
    }[text(value)] ?? "审核意见"
  );
}

function targetLabel(value: unknown, version: unknown) {
  return text(value) === "PROTOCOL_SECTION"
    ? `方案章节 v${count(version)}`
    : "STROBE 检查项";
}

function selectedReviewTarget() {
  return asRecords(review.value.commentTargets).find(
    (target) => text(target.targetKey) === reviewTargetKey.value,
  );
}

async function addComment() {
  const target = selectedReviewTarget();
  if (!target || !reviewComment.value.trim()) return;
  await workspace.runAction(
    props.projectKey,
    "ADD_INTERNAL_REVIEW_COMMENT",
    {
      targetType: text(target.targetType),
      targetKey: text(target.targetKey),
      targetVersion: count(target.targetVersion),
      commentType: reviewCommentType.value,
      responsibility: reviewResponsibility.value,
      content: reviewComment.value.trim(),
    },
    "review",
  );
}

async function submitDecision(actionCode: string) {
  if (!reviewSummary.value.trim()) return;
  await workspace.runAction(
    props.projectKey,
    actionCode,
    {
      decision: reviewDecision.value,
      summary: reviewSummary.value.trim(),
      reviewVersion: count(review.value.version),
    },
    "review",
  );
}

async function confirmOwnerReview() {
  await workspace.runAction(
    props.projectKey,
    "CONFIRM_INTERNAL_REVIEW",
    { reviewVersion: count(review.value.version) },
    "review",
  );
}
</script>

<template>
  <section
    v-if="Object.keys(review).length"
    class="v2-section-card"
    aria-labelledby="internal-review-title"
  >
    <h3 id="internal-review-title">
      第 {{ count(review.reviewRoundNo) }} 轮内部审核
    </h3>
    <dl class="v2-review-grid">
      <div>
        <dt>医学审核</dt>
        <dd>{{ statusLabel(review.medicalDecision) }}</dd>
      </div>
      <div>
        <dt>统计审核</dt>
        <dd>{{ statusLabel(review.statisticalDecision) }}</dd>
      </div>
      <div>
        <dt>负责人确认</dt>
        <dd>{{ flag(review.ownerConfirmed) ? "已确认" : "待确认" }}</dd>
      </div>
    </dl>
    <section
      v-if="asRecords(review.comments).length"
      aria-labelledby="review-comments-title"
    >
      <h4 id="review-comments-title">
        审核批注
      </h4>
      <ul class="v2-detail-list">
        <li
          v-for="item in asRecords(review.comments)"
          :key="text(item.commentKey)"
        >
          <strong>
            {{ responsibilityLabel(item.responsibility) }} ·
            {{ commentTypeLabel(item.commentType) }} ·
            {{ targetLabel(item.targetType, item.targetVersion) }}
          </strong>
          <span>{{ text(item.content) }}</span>
        </li>
      </ul>
    </section>
    <ol class="v2-history-list">
      <li
        v-for="item in asRecords(review.history)"
        :key="`${text(item.actionType)}-${text(item.occurredAt)}`"
      >
        <strong>{{ reviewActionLabel(item.actionType) }}</strong>
        <span>{{ text(item.summary) }}</span>
      </li>
    </ol>
    <form
      v-if="actionAllowed('ADD_INTERNAL_REVIEW_COMMENT')"
      class="v2-form-grid"
      @submit.prevent="addComment"
    >
      <h4>添加可定位批注</h4>
      <label class="v2-field">
        <span>审核职责</span>
        <el-select v-model="reviewResponsibility">
          <el-option
            label="医学审核"
            value="MEDICAL_REVIEW"
          />
          <el-option
            label="统计审核"
            value="STATISTICAL_REVIEW"
          />
        </el-select>
      </label>
      <label class="v2-field">
        <span>定位目标</span>
        <el-select
          v-model="reviewTargetKey"
          filterable
        >
          <el-option
            v-for="target in asRecords(review.commentTargets)"
            :key="text(target.targetKey)"
            :label="text(target.label)"
            :value="text(target.targetKey)"
          />
        </el-select>
      </label>
      <label class="v2-field">
        <span>批注类型</span>
        <el-select v-model="reviewCommentType">
          <el-option
            label="医学"
            value="MEDICAL"
          />
          <el-option
            label="统计"
            value="STATISTICAL"
          />
          <el-option
            label="报告规范"
            value="REPORTING"
          />
          <el-option
            label="一般意见"
            value="GENERAL"
          />
        </el-select>
      </label>
      <label class="v2-field">
        <span>批注内容</span>
        <el-input
          v-model="reviewComment"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
        />
      </label>
      <el-button
        native-type="submit"
        :loading="workspace.actionPending"
        :disabled="!reviewTargetKey || !reviewComment.trim()"
      >
        保存批注
      </el-button>
    </form>
    <form
      v-if="
        actionAllowed('SUBMIT_MEDICAL_REVIEW') ||
          actionAllowed('SUBMIT_STATISTICAL_REVIEW')
      "
      class="v2-form-grid"
      @submit.prevent
    >
      <h4>提交审核决定</h4>
      <label class="v2-field">
        <span>决定</span>
        <el-select v-model="reviewDecision">
          <el-option
            label="通过"
            value="APPROVE"
          />
          <el-option
            label="退回修订"
            value="RETURN_FOR_REVISION"
          />
        </el-select>
      </label>
      <label class="v2-field">
        <span>审核总结</span>
        <el-input
          v-model="reviewSummary"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
        />
      </label>
      <div class="v2-button-row">
        <el-button
          v-if="actionAllowed('SUBMIT_MEDICAL_REVIEW')"
          type="primary"
          :loading="workspace.actionPending"
          :disabled="!reviewSummary.trim()"
          @click="submitDecision('SUBMIT_MEDICAL_REVIEW')"
        >
          提交医学审核
        </el-button>
        <el-button
          v-if="actionAllowed('SUBMIT_STATISTICAL_REVIEW')"
          type="primary"
          :loading="workspace.actionPending"
          :disabled="!reviewSummary.trim()"
          @click="submitDecision('SUBMIT_STATISTICAL_REVIEW')"
        >
          提交统计审核
        </el-button>
      </div>
    </form>
    <el-button
      v-if="actionAllowed('CONFIRM_INTERNAL_REVIEW')"
      type="primary"
      :loading="workspace.actionPending"
      @click="confirmOwnerReview"
    >
      负责人确认并锁定当前版本
    </el-button>
  </section>
</template>
