<script setup lang="ts">
import type {
  StageView,
  TodoItem,
  WorkspaceConnectionState,
  WorkspaceSummary,
} from '../../types/workspace'

const props = defineProps<{
  summary: WorkspaceSummary
  stages: StageView[]
  todos: TodoItem[]
  section: string
  connection: WorkspaceConnectionState
  legacyEnabled: boolean
}>()

const emit = defineEmits<{
  refresh: []
}>()

const stageStatusLabels: Record<StageView['status'], string> = {
  NOT_STARTED: '尚未开始',
  IN_PROGRESS: '处理中',
  WAITING_USER: '待您处理',
  BLOCKED: '存在阻塞',
  FAILED: '处理失败',
  COMPLETED: '已完成',
}

function connectionLabel() {
  return props.connection === 'CONNECTED'
    ? '状态已同步'
    : props.connection === 'CONNECTING'
      ? '正在连接'
      : '连接中断，正在重试'
}
</script>

<template>
  <section class="v2-project-shell">
    <header class="v2-project-heading">
      <div>
        <router-link
          class="v2-back-link"
          to="/workspace"
        >
          ← 我的课题
        </router-link>
        <h1>{{ summary.displayName }}</h1>
        <div class="v2-project-meta">
          <span class="v2-status-pill">{{ summary.businessStatus.label }}</span>
          <span
            class="v2-connection"
            :class="{ offline: connection !== 'CONNECTED' }"
            role="status"
          >
            {{ connectionLabel() }}
          </span>
        </div>
      </div>
      <el-button
        plain
        @click="emit('refresh')"
      >
        刷新状态
      </el-button>
    </header>

    <div class="v2-workspace-layout">
      <nav
        class="v2-stage-nav"
        aria-label="课题阶段"
      >
        <router-link
          :to="`/projects/${summary.projectKey}/overview`"
          :class="{ active: section === 'overview' }"
        >
          <strong>课题概览</strong>
          <span>{{ summary.progress.percent }}% 完成</span>
        </router-link>
        <router-link
          :to="`/projects/${summary.projectKey}/models`"
          :class="{ active: section === 'models' }"
        >
          <strong>模型治理</strong>
          <span>路由、用量与预算</span>
        </router-link>
        <router-link
          v-for="stage in stages"
          :key="stage.code"
          :to="stage.targetRoute"
          :class="{ active: stage.targetRoute.endsWith(`/${section}`) }"
        >
          <strong>{{ stage.label }}</strong>
          <span>{{ stageStatusLabels[stage.status] }}</span>
        </router-link>
      </nav>

      <div class="v2-project-content">
        <slot v-if="section !== 'overview'" />

        <template v-else>
          <section
            class="v2-summary-hero"
            aria-labelledby="project-overview-title"
          >
            <span class="eyebrow">当前阶段</span>
            <h2 id="project-overview-title">
              {{ summary.currentStage.label }}
            </h2>
            <p>{{ summary.currentStage.summary }}</p>
            <router-link
              v-if="summary.nextAction.enabled"
              class="v2-primary-link"
              :to="summary.nextAction.targetRoute"
            >
              {{ summary.nextAction.label }}
            </router-link>
          </section>

          <section
            v-if="summary.blockedReasons.length"
            class="v2-warning-card"
            aria-labelledby="blocked-reasons-title"
          >
            <h2 id="blocked-reasons-title">
              需要注意
            </h2>
            <ul>
              <li
                v-for="reason in summary.blockedReasons"
                :key="reason.code"
              >
                {{ reason.message }}
              </li>
            </ul>
          </section>

          <section
            class="v2-section-card"
            aria-labelledby="project-todos-title"
          >
            <h2 id="project-todos-title">
              本课题待办
            </h2>
            <div
              v-if="todos.length"
              class="v2-compact-todos"
            >
              <router-link
                v-for="todo in todos"
                :key="todo.todoKey"
                :to="todo.targetRoute"
              >
                <strong>{{ todo.title }}</strong>
                <span>{{ todo.description }}</span>
              </router-link>
            </div>
            <p
              v-else
              class="v2-muted"
            >
              当前没有需要您处理的事项。
            </p>
          </section>
        </template>

        <footer
          v-if="legacyEnabled"
          class="v2-legacy-bridge"
        >
          <span>需要处理尚未迁移的功能？</span>
          <router-link to="/workspace/legacy">
            在旧版工作台继续
          </router-link>
        </footer>
      </div>
    </div>
  </section>
</template>
