<script setup lang="ts">
import { computed } from "vue";
import type { ArtifactSectionView } from "../../../types/workspace";
import { asRecord, asRecords, count, text } from "../artifactData";

const props = defineProps<{ data: ArtifactSectionView }>();
const content = computed(() => asRecord(props.data.content));
const claims = computed(() => asRecord(content.value.claimCitation));
const strobe = computed(() => asRecord(content.value.strobe));

function statusLabel(value: unknown) {
  return (
    {
      COVERED: "已覆盖",
      PARTIALLY_COVERED: "部分覆盖",
      MISSING: "缺失",
      NOT_APPLICABLE: "不适用",
      NEEDS_EXPERT_REVIEW: "待专家复核",
    }[text(value)] ?? "待处理"
  );
}
</script>

<template>
  <section
    v-if="Object.keys(claims).length"
    class="v2-section-card"
    aria-labelledby="claim-citation-title"
  >
    <h3 id="claim-citation-title">
      主张—引用预检查
    </h3>
    <div
      v-for="claim in asRecords(claims.claims)"
      :key="text(claim.claimKey)"
      class="v2-result-card"
    >
      <strong>{{ text(claim.claimText) }}</strong>
      <p>{{ statusLabel(claim.supportStatus) }}</p>
    </div>
  </section>
  <section
    v-if="Object.keys(strobe).length"
    class="v2-section-card"
    aria-labelledby="strobe-title"
  >
    <h3 id="strobe-title">
      STROBE 报告完整性预检查
    </h3>
    <p>
      已覆盖 {{ count(strobe.coveredCount) }}； 部分覆盖
      {{ count(strobe.partiallyCoveredCount) }}； 缺失
      {{ count(strobe.missingCount) }}； 待专家复核
      {{ count(strobe.needsExpertReviewCount) }}
    </p>
    <div
      v-for="item in asRecords(strobe.items)"
      :key="text(item.checkItemKey)"
      class="v2-result-card"
    >
      <strong>{{ text(item.itemCode) }} · {{ statusLabel(item.status) }}</strong>
      <p>{{ text(item.requirementSummary) }}</p>
      <p>{{ text(item.suggestion) }}</p>
    </div>
  </section>
</template>
