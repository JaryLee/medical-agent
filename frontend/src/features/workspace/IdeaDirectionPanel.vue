<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useWorkspaceV2Store } from '../../stores/workspaceV2'
import type { IdeaDirectionView } from '../../types/workspace'
import {
  clearClarificationDraft,
  clearIdeaDraft,
  readClarificationDraft,
  readIdeaDraft,
  writeClarificationDraft,
  writeIdeaDraft,
} from './workspaceState'

const props = defineProps<{
  projectKey: string
  data?: IdeaDirectionView
}>()

const emit = defineEmits<{
  'dirty-change': [dirty: boolean]
}>()

const store = useWorkspaceV2Store()
const ideaDraft = ref('')
const answers = reactive<Record<string, string>>({})
const selectedDirection = ref('')
const validationMessage = ref('')

const actions = computed(() =>
  new Map((props.data?.allowedActions ?? []).map((action) => [action.code, action])),
)
const canStart = computed(() => actions.value.has('START_RESEARCH_IDEA'))
const canClarify = computed(() => actions.value.has('SUBMIT_CLARIFICATIONS'))
const canConfirm = computed(() => actions.value.has('CONFIRM_RESEARCH_DIRECTION'))
const canCancel = computed(() => actions.value.has('CANCEL_RESEARCH_WORKFLOW'))
const canRetry = computed(() => actions.value.has('RETRY_RESEARCH_WORKFLOW'))
const hasDirtyIdea = computed(() => canStart.value && Boolean(ideaDraft.value.trim()))
const hasDirtyAnswers = computed(() =>
  canClarify.value && Object.values(answers).some((answer) => answer.trim()),
)
const dirty = computed(() => hasDirtyIdea.value || hasDirtyAnswers.value)

watch(
  () => props.projectKey,
  () => {
    ideaDraft.value = readIdeaDraft(props.projectKey)
    Object.keys(answers).forEach((key) => delete answers[key])
    Object.assign(answers, readClarificationDraft(props.projectKey))
    selectedDirection.value =
      props.data?.directionCandidates?.candidates.find((item) => item.selected)
        ?.directionKey ?? ''
  },
  { immediate: true },
)

watch(
  () => props.data,
  (data) => {
    selectedDirection.value =
      data?.directionCandidates?.candidates.find((item) => item.selected)
        ?.directionKey ?? ''
  },
  { deep: true },
)

watch(ideaDraft, (value) => {
  writeIdeaDraft(props.projectKey, value)
  emit('dirty-change', dirty.value)
})
watch(
  answers,
  (value) => {
    writeClarificationDraft(props.projectKey, value)
    emit('dirty-change', dirty.value)
  },
  { deep: true },
)
watch(dirty, (value) => emit('dirty-change', value), { immediate: true })

async function submitIdea() {
  validationMessage.value = ''
  const idea = ideaDraft.value.trim()
  if (!idea) {
    validationMessage.value = '请填写研究构想。'
    return
  }
  if (idea.length > 2000) {
    validationMessage.value = '研究构想不能超过 2000 字。'
    return
  }
  if (
    await store.runAction(
      props.projectKey,
      'START_RESEARCH_IDEA',
      { idea },
      'idea',
    )
  ) {
    clearIdeaDraft(props.projectKey)
    ideaDraft.value = ''
  }
}

async function submitClarifications() {
  validationMessage.value = ''
  const questions = props.data?.currentClarificationQuestions ?? []
  const normalized = Object.fromEntries(
    questions.map((question) => [question, (answers[question] ?? '').trim()]),
  )
  if (Object.values(normalized).some((answer) => !answer)) {
    validationMessage.value = '请逐项填写全部澄清信息。'
    return
  }
  if (
    await store.runAction(
      props.projectKey,
      'SUBMIT_CLARIFICATIONS',
      { answers: normalized },
      'idea',
    )
  ) {
    clearClarificationDraft(props.projectKey)
    Object.keys(answers).forEach((key) => delete answers[key])
  }
}

async function confirmDirection() {
  validationMessage.value = ''
  if (!selectedDirection.value) {
    validationMessage.value = '请选择一个研究方向。'
    return
  }
  await store.runAction(
    props.projectKey,
    'CONFIRM_RESEARCH_DIRECTION',
    { directionKey: selectedDirection.value },
    'idea',
  )
}

function runSimpleAction(action: string) {
  void store.runAction(props.projectKey, action, {}, 'idea')
}
</script>

<template>
  <section aria-labelledby="idea-direction-title">
    <header class="v2-content-heading">
      <span class="eyebrow">研究构想与方向</span>
      <h2 id="idea-direction-title">
        {{ data?.workflowStatus.label ?? '加载研究构想' }}
      </h2>
      <p>{{ data?.disclaimer }}</p>
    </header>

    <form
      v-if="canStart"
      class="v2-section-card"
      @submit.prevent="submitIdea"
    >
      <h3>提交研究构想</h3>
      <label for="research-idea">请描述研究对象、关注因素和预期结局</label>
      <el-input
        id="research-idea"
        v-model="ideaDraft"
        type="textarea"
        :rows="7"
        maxlength="2000"
        show-word-limit
        placeholder="例如：研究本院2型糖尿病患者不同用药方案与肾功能变化的关联……"
        :disabled="store.actionPending"
      />
      <p class="v2-draft-note">
        未提交内容仅保存在当前浏览器；使用共享设备时请提交后退出。
      </p>
      <el-button
        type="primary"
        native-type="submit"
        :loading="store.actionPending"
      >
        提交构想并生成澄清问题
      </el-button>
    </form>

    <section
      v-if="data?.idea"
      class="v2-section-card"
    >
      <div class="v2-card-topline">
        <h3>已提交的研究构想</h3>
        <span class="v2-status-pill">{{ data.idea.statusLabel }}</span>
      </div>
      <p class="v2-idea-copy">
        {{ data.idea.content }}
      </p>
    </section>

    <form
      v-if="canClarify"
      class="v2-section-card"
      @submit.prevent="submitClarifications"
    >
      <h3>补充研究信息</h3>
      <p class="v2-muted">
        请逐项回答，系统会据此生成三个可比较的研究方向。
      </p>
      <div
        v-for="(question, index) in data?.currentClarificationQuestions"
        :key="question"
        class="v2-question-field"
      >
        <label :for="`clarification-${index}`">{{ question }}</label>
        <el-input
          :id="`clarification-${index}`"
          v-model="answers[question]"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          :disabled="store.actionPending"
        />
      </div>
      <p class="v2-draft-note">
        未提交答案会保存在当前浏览器。
      </p>
      <el-button
        type="primary"
        native-type="submit"
        :loading="store.actionPending"
      >
        提交全部补充信息
      </el-button>
    </form>

    <section
      v-if="data?.directionCandidates"
      class="v2-section-card"
      aria-labelledby="direction-candidates-title"
    >
      <h3 id="direction-candidates-title">
        候选研究方向
      </h3>
      <p class="v2-muted">
        请比较研究类型与局限性，再确认一个方向。
      </p>
      <el-radio-group
        v-model="selectedDirection"
        class="v2-direction-list"
        :disabled="!canConfirm || store.actionPending"
      >
        <el-radio
          v-for="candidate in data.directionCandidates.candidates"
          :key="candidate.directionKey"
          :value="candidate.directionKey"
          class="v2-direction-option"
        >
          <span class="v2-direction-title">{{ candidate.title }}</span>
          <span>{{ candidate.recommendedStudyType.label }}</span>
          <small v-if="candidate.limitations.length">
            局限：{{ candidate.limitations.join('；') }}
          </small>
        </el-radio>
      </el-radio-group>
      <el-button
        v-if="canConfirm"
        type="primary"
        :loading="store.actionPending"
        @click="confirmDirection"
      >
        确认所选研究方向
      </el-button>
    </section>

    <section
      v-if="data?.clarificationHistory.length"
      class="v2-section-card"
      aria-labelledby="clarification-history-title"
    >
      <h3 id="clarification-history-title">
        补充信息记录
      </h3>
      <details
        v-for="round in data.clarificationHistory"
        :key="round.roundNo"
      >
        <summary>第 {{ round.roundNo }} 轮补充</summary>
        <dl class="v2-answer-list">
          <template
            v-for="question in round.questions"
            :key="question"
          >
            <dt>{{ question }}</dt>
            <dd>{{ round.answers[question] }}</dd>
          </template>
        </dl>
      </details>
    </section>

    <section
      v-if="!canStart && !canClarify && !canConfirm"
      class="v2-processing-card"
      role="status"
    >
      <h3>{{ data?.workflowStatus.label ?? '正在加载' }}</h3>
      <p>
        {{
          canRetry
            ? '当前处理未完成，确认输入无误后可以重试。'
            : '系统正在推进课题；状态变化后本页面会自动刷新。'
        }}
      </p>
      <el-button
        v-if="canRetry"
        type="primary"
        :loading="store.actionPending"
        @click="runSimpleAction('RETRY_RESEARCH_WORKFLOW')"
      >
        重试当前阶段
      </el-button>
    </section>

    <p
      v-if="validationMessage"
      class="v2-form-error"
      role="alert"
    >
      {{ validationMessage }}
    </p>

    <el-button
      v-if="canCancel"
      class="v2-cancel-action"
      plain
      type="danger"
      :loading="store.actionPending"
      @click="runSimpleAction('CANCEL_RESEARCH_WORKFLOW')"
    >
      取消当前处理
    </el-button>
  </section>
</template>
