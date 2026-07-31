<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
} from 'vue-router'
import { featureFlags } from '../config/featureFlags'
import IdeaDirectionPanel from '../features/workspace/IdeaDirectionPanel.vue'
import ProjectWorkspace from '../features/workspace/ProjectWorkspace.vue'
import WorkspaceArtifactPanel from '../features/workspace/WorkspaceArtifactPanel.vue'
import WorkspaceDashboard from '../features/workspace/WorkspaceDashboard.vue'
import WorkspaceLoginPanel from '../features/workspace/WorkspaceLoginPanel.vue'
import ModelGovernancePanel from '../features/workspace/ModelGovernancePanel.vue'
import ModelEvaluationPanel from '../features/workspace/ModelEvaluationPanel.vue'
import { useSessionStore } from '../stores/session'
import { useWorkspaceV2Store } from '../stores/workspaceV2'

const route = useRoute()
const session = useSessionStore()
const workspace = useWorkspaceV2Store()
const bootstrapping = ref(true)
const loginBusy = ref(false)
const loginError = ref('')
const hasUnsavedDraft = ref(false)

const projectKey = computed(() =>
  typeof route.params.projectKey === 'string'
    ? route.params.projectKey
    : '',
)
const section = computed(() =>
  typeof route.params.section === 'string'
    ? route.params.section
    : 'overview',
)
const dashboardMode = computed<'home' | 'todos'>(() =>
  route.name === 'workspace-todos' ? 'todos' : 'home',
)
const isProjectRoute = computed(() => route.name === 'workspace-project')
const isEvaluationRoute = computed(() => route.name === 'model-evaluations')
const needsIdeaDetails = computed(() =>
  ['idea', 'direction'].includes(section.value),
)
const needsArtifactDetails = computed(() =>
  [
    'evidence',
    'design',
    'protocol',
    'statistics',
    'quality',
    'review',
    'export',
  ].includes(section.value),
)

async function loadCurrentRoute() {
  if (!session.user || session.user.forcePasswordChange) return
  if (isProjectRoute.value && projectKey.value) {
    await workspace.loadProject(projectKey.value, section.value)
    if (workspace.currentSummary?.projectKey === projectKey.value) {
      workspace.connect(projectKey.value, section.value)
    }
  } else if (!isEvaluationRoute.value) {
    workspace.disconnect()
    await workspace.loadDashboard()
  } else {
    workspace.disconnect()
  }
}

async function signIn(credentials: {
  hospitalCode: string
  username: string
  password: string
}) {
  loginBusy.value = true
  loginError.value = ''
  try {
    const user = await session.signIn(
      credentials.hospitalCode,
      credentials.username,
      credentials.password,
    )
    if (user.forcePasswordChange) {
      loginError.value = '首次登录需要先在旧版入口修改密码。'
      return
    }
    await loadCurrentRoute()
  } catch {
    loginError.value = '登录失败，请检查医院编码、用户名和密码。'
  } finally {
    loginBusy.value = false
  }
}

function retry() {
  void loadCurrentRoute()
}

function confirmNavigation() {
  return !hasUnsavedDraft.value
    || window.confirm('当前页面还有未提交的内容，确定离开吗？')
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!hasUnsavedDraft.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => confirmNavigation())
onBeforeRouteUpdate(() => confirmNavigation())

watch(
  () => route.fullPath,
  () => {
    hasUnsavedDraft.value = false
    void loadCurrentRoute()
  },
)

onMounted(async () => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  await session.restore()
  bootstrapping.value = false
  if (session.user && !session.user.forcePasswordChange) {
    await loadCurrentRoute()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  workspace.disconnect()
})
</script>

<template>
  <div class="workspace-v2">
    <div
      v-if="bootstrapping"
      class="v2-loading-state"
      role="status"
    >
      正在恢复工作台……
    </div>

    <WorkspaceLoginPanel
      v-else-if="!session.user"
      :busy="loginBusy"
      :error-message="loginError"
      @submit="signIn"
    />

    <section
      v-else-if="session.user.forcePasswordChange"
      class="v2-empty-state"
      role="alert"
    >
      <h1>需要先修改初始密码</h1>
      <p>为保护课题数据，首次登录必须先完成密码修改。</p>
      <router-link
        v-if="featureFlags.legacyWorkspaceEnabled"
        class="v2-primary-link"
        to="/workspace/legacy"
      >
        前往旧版入口修改密码
      </router-link>
    </section>

    <template v-else>
      <el-alert
        v-if="workspace.errorMessage"
        :title="workspace.errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="v2-error-banner"
      >
        <template #default>
          <el-button
            link
            type="primary"
            @click="retry"
          >
            重新获取
          </el-button>
        </template>
      </el-alert>

      <div
        v-if="workspace.loading && !workspace.currentSummary && isProjectRoute"
        class="v2-loading-state"
        role="status"
      >
        正在读取课题状态……
      </div>

      <ProjectWorkspace
        v-else-if="isProjectRoute && workspace.currentSummary"
        :summary="workspace.currentSummary"
        :stages="workspace.currentStages"
        :todos="workspace.currentTodos"
        :section="section"
        :connection="workspace.connection"
        :legacy-enabled="featureFlags.legacyWorkspaceEnabled"
        @refresh="retry"
      >
        <IdeaDirectionPanel
          v-if="needsIdeaDetails"
          :project-key="projectKey"
          :data="workspace.ideaDirection"
          @dirty-change="hasUnsavedDraft = $event"
        />
        <WorkspaceArtifactPanel
          v-else-if="needsArtifactDetails && workspace.currentArtifact"
          :project-key="projectKey"
          :section="section"
          :data="workspace.currentArtifact"
          @dirty-change="hasUnsavedDraft = $event"
        />
        <ModelGovernancePanel
          v-else-if="section === 'models'"
          :project-key="projectKey"
        />
        <section
          v-else
          class="v2-empty-state"
        >
          <h2>{{ workspace.currentSummary.currentStage.label }}</h2>
          <p>
            该阶段将在下一批纵向切片迁入 V2。当前可通过固定旧版入口继续，
            已完成的状态会同步回本页。
          </p>
          <router-link
            v-if="featureFlags.legacyWorkspaceEnabled"
            class="v2-primary-link"
            to="/workspace/legacy"
          >
            在旧版工作台继续
          </router-link>
        </section>
      </ProjectWorkspace>

      <WorkspaceDashboard
        v-else-if="!isProjectRoute && !isEvaluationRoute"
        :mode="dashboardMode"
        :projects="workspace.projects"
        :todos="workspace.todos"
        :legacy-enabled="featureFlags.legacyWorkspaceEnabled"
      />
      <ModelEvaluationPanel v-else-if="isEvaluationRoute" />
    </template>
  </div>
</template>
