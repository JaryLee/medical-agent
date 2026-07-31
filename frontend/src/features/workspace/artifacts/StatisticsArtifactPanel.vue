<script setup lang="ts">
import { computed } from "vue";
import type { ArtifactSectionView } from "../../../types/workspace";
import { asRecord, asRecords, asStrings, text } from "../artifactData";

const props = defineProps<{ data: ArtifactSectionView }>();
const content = computed(() => asRecord(props.data.content));
const statistics = computed(() => asRecord(content.value.statisticalDraft));

function valueStatusLabel(value: unknown) {
  return (
    {
      PROVIDED: "已提供",
      DERIVED: "已推导，待复核",
      NEEDS_INPUT: "需要补充参数",
      MISSING_NEEDS_INPUT: "缺少必要参数",
      NOT_APPLICABLE: "不适用",
    }[text(value)] ?? "待确认"
  );
}
</script>

<template>
  <section
    v-if="Object.keys(statistics).length"
    class="v2-section-card"
    aria-labelledby="statistics-title"
  >
    <h3 id="statistics-title">
      统计分析计划草案
    </h3>
    <p>主要结局：{{ text(statistics.primaryOutcome) }}</p>
    <h4>主要分析候选</h4>
    <ul>
      <li
        v-for="item in asStrings(statistics.primaryAnalysisCandidates)"
        :key="item"
      >
        {{ item }}
      </li>
    </ul>
    <h4>样本量参数</h4>
    <div
      v-for="parameter in asRecords(statistics.sampleSizeParameters)"
      :key="text(parameter.code)"
      class="v2-result-card"
    >
      <strong>{{ text(parameter.label) }}</strong>
      <p>
        {{ valueStatusLabel(parameter.valueStatus) }} ·
        {{ text(parameter.rationale) }}
      </p>
    </div>
  </section>
</template>
