<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { ArtifactSectionView } from "../../../types/workspace";
import { useWorkspaceV2Store } from "../../../stores/workspaceV2";
import { asRecord, asRecords, count, text } from "../artifactData";

const props = defineProps<{
  projectKey: string;
  data: ArtifactSectionView;
}>();
const workspace = useWorkspaceV2Store();
const content = computed(() => asRecord(props.data.content));
const templates = computed(() => asRecords(content.value.templates));
const citationStyles = computed(() => asRecords(content.value.citationStyles));
const completedExport = computed(() => asRecord(content.value.completedExport));
const selectedTemplateKey = ref("");
const selectedStyleKey = ref("");
const confirmReviewedContent = ref(false);

watch(
  content,
  () => {
    selectedTemplateKey.value = text(templates.value[0]?.templateKey);
    selectedStyleKey.value = text(citationStyles.value[0]?.styleKey);
    confirmReviewedContent.value = false;
  },
  { immediate: true },
);

function actionAllowed(code: string) {
  return props.data.allowedActions.some(
    (action) => action.code === code && action.enabled,
  );
}

async function exportDraft() {
  if (
    !selectedTemplateKey.value ||
    !selectedStyleKey.value ||
    !confirmReviewedContent.value
  )
    return;
  await workspace.runAction(
    props.projectKey,
    "EXPORT_RESEARCH_DRAFT",
    {
      templateKey: selectedTemplateKey.value,
      styleKey: selectedStyleKey.value,
      confirmReviewedContent: true,
    },
    "export",
  );
}
</script>

<template>
  <section
    class="v2-section-card"
    aria-labelledby="draft-export-title"
  >
    <h3 id="draft-export-title">
      科研草案导出
    </h3>
    <template v-if="Object.keys(completedExport).length">
      <p>
        {{ text(completedExport.fileName) }} ·
        {{ count(completedExport.contentSize) }} bytes
      </p>
      <a
        class="v2-primary-link"
        :href="text(completedExport.downloadUrl)"
      >
        下载科研草案
      </a>
    </template>
    <template v-else>
      <p>
        可用模板 {{ templates.length }} 个，引用格式
        {{ citationStyles.length }} 个。
      </p>
      <p>完成医学、统计和负责人三方确认后才可导出。</p>
      <form
        v-if="actionAllowed('EXPORT_RESEARCH_DRAFT')"
        class="v2-form-grid"
        @submit.prevent="exportDraft"
      >
        <label class="v2-field">
          <span>文档模板</span>
          <el-select v-model="selectedTemplateKey">
            <el-option
              v-for="item in templates"
              :key="text(item.templateKey)"
              :label="`${text(item.templateName)} · v${count(item.versionNo)}`"
              :value="text(item.templateKey)"
            />
          </el-select>
        </label>
        <label class="v2-field">
          <span>引用格式</span>
          <el-select v-model="selectedStyleKey">
            <el-option
              v-for="item in citationStyles"
              :key="text(item.styleKey)"
              :label="`${text(item.styleName)} · v${count(item.versionNo)}`"
              :value="text(item.styleKey)"
            />
          </el-select>
        </label>
        <el-checkbox v-model="confirmReviewedContent">
          我确认导出的是当前已审核锁定版本，文件仅为科研草案
        </el-checkbox>
        <el-button
          native-type="submit"
          type="primary"
          :loading="workspace.actionPending"
          :disabled="
            !selectedTemplateKey || !selectedStyleKey || !confirmReviewedContent
          "
        >
          生成科研草案
        </el-button>
      </form>
    </template>
  </section>
</template>
