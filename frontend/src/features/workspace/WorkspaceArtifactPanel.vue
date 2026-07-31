<script setup lang="ts">
import { computed, defineAsyncComponent, watch } from "vue";
import type { ArtifactSectionView } from "../../types/workspace";

const props = defineProps<{
  projectKey: string;
  section: string;
  data: ArtifactSectionView;
}>();
const emit = defineEmits<{
  dirtyChange: [dirty: boolean];
}>();

const artifactComponents = {
  evidence: defineAsyncComponent(
    () => import("./artifacts/EvidenceArtifactPanel.vue"),
  ),
  design: defineAsyncComponent(
    () => import("./artifacts/DesignArtifactPanel.vue"),
  ),
  protocol: defineAsyncComponent(
    () => import("./artifacts/ProtocolArtifactPanel.vue"),
  ),
  statistics: defineAsyncComponent(
    () => import("./artifacts/StatisticsArtifactPanel.vue"),
  ),
  quality: defineAsyncComponent(
    () => import("./artifacts/QualityArtifactPanel.vue"),
  ),
  review: defineAsyncComponent(
    () => import("./artifacts/ReviewArtifactPanel.vue"),
  ),
  export: defineAsyncComponent(
    () => import("./artifacts/ExportArtifactPanel.vue"),
  ),
} as const;

const activeComponent = computed(
  () => artifactComponents[props.section as keyof typeof artifactComponents],
);

watch(
  () => props.section,
  (section) => {
    if (section !== "protocol") emit("dirtyChange", false);
  },
  { immediate: true },
);

function stageStatusLabel(value: string) {
  return (
    {
      NOT_STARTED: "尚未开始",
      IN_PROGRESS: "进行中",
      WAITING_USER: "等待确认",
      WAITING_REVIEW: "等待审核",
      REVISION_REQUIRED: "需要修订",
      BLOCKED: "暂时阻塞",
      COMPLETED: "已完成",
      FAILED: "执行失败",
      CANCELLED: "已取消",
    }[value] ?? "待处理"
  );
}
</script>

<template>
  <article class="v2-artifact">
    <header class="v2-artifact-heading">
      <div>
        <span class="eyebrow">{{ data.status.label }}</span>
        <h2>{{ data.title }}</h2>
      </div>
      <span class="v2-status-pill">
        {{ stageStatusLabel(data.status.code) }}
      </span>
    </header>

    <el-alert
      :title="data.disclaimer"
      type="warning"
      :closable="false"
      show-icon
      class="v2-artifact-disclaimer"
    />

    <Suspense v-if="activeComponent">
      <component
        :is="activeComponent"
        :project-key="projectKey"
        :data="data"
        @dirty-change="emit('dirtyChange', $event)"
      />
      <template #fallback>
        <div
          class="v2-loading-state"
          role="status"
        >
          正在加载本阶段内容……
        </div>
      </template>
    </Suspense>

    <section
      v-if="Object.keys(data.content).length === 0"
      class="v2-empty-state"
      role="status"
    >
      <h3>当前阶段尚未形成可展示结果</h3>
      <p>系统会在前序步骤完成后更新本页，无需从技术任务状态中自行判断。</p>
    </section>
  </article>
</template>
