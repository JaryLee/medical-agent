<script setup lang="ts">
import type { TodoItem, WorkspaceSummary } from '../../types/workspace'

defineProps<{
  mode: 'home' | 'todos'
  projects: WorkspaceSummary[]
  todos: TodoItem[]
  legacyEnabled: boolean
}>()

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section
    v-if="mode === 'home'"
    aria-labelledby="workspace-home-title"
  >
    <header class="v2-page-heading">
      <div>
        <span class="eyebrow">我的科研课题</span>
        <h1 id="workspace-home-title">
          从待处理事项继续工作
        </h1>
        <p>这里展示服务端确认的课题阶段、进度和下一步动作。</p>
      </div>
      <router-link
        class="v2-secondary-link"
        to="/todos"
      >
        查看全部待办
        <span
          class="v2-count"
          aria-label="待办数量"
        >{{ todos.length }}</span>
      </router-link>
    </header>

    <div
      v-if="projects.length"
      class="v2-project-grid"
    >
      <article
        v-for="project in projects"
        :key="project.projectKey"
        class="v2-project-card"
      >
        <div class="v2-card-topline">
          <span class="v2-status-pill">{{ project.businessStatus.label }}</span>
          <time :datetime="project.lastUpdatedAt">
            {{ formatTime(project.lastUpdatedAt) }}
          </time>
        </div>
        <h2>{{ project.displayName }}</h2>
        <p>{{ project.currentStage.label }} · {{ project.currentStage.summary }}</p>
        <div
          class="v2-progress"
          role="progressbar"
          :aria-label="`${project.displayName}完成进度`"
          :aria-valuenow="project.progress.percent"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <span :style="{ width: `${project.progress.percent}%` }" />
        </div>
        <div class="v2-card-footer">
          <span>
            {{ project.progress.completed }}/{{ project.progress.total }} 个阶段
          </span>
          <router-link :to="project.nextAction.targetRoute">
            {{ project.nextAction.label }}
          </router-link>
        </div>
      </article>
    </div>

    <div
      v-else
      class="v2-empty-state"
    >
      <h2>还没有可见课题</h2>
      <p>新建课题目前仍由旧版工作台承接，创建后会自动出现在这里。</p>
      <router-link
        v-if="legacyEnabled"
        class="v2-primary-link"
        to="/workspace/legacy"
      >
        前往旧版创建课题
      </router-link>
    </div>
  </section>

  <section
    v-else
    aria-labelledby="workspace-todos-title"
  >
    <header class="v2-page-heading">
      <div>
        <span class="eyebrow">待办中心</span>
        <h1 id="workspace-todos-title">
          需要我处理
        </h1>
        <p>待办由课题事实状态生成，不在浏览器中推测。</p>
      </div>
      <router-link
        class="v2-secondary-link"
        to="/workspace"
      >
        返回我的课题
      </router-link>
    </header>
    <div
      v-if="todos.length"
      class="v2-todo-list"
    >
      <article
        v-for="todo in todos"
        :key="todo.todoKey"
        class="v2-todo-card"
      >
        <div>
          <span class="v2-status-pill">{{ todo.todoType.label }}</span>
          <h2>{{ todo.title }}</h2>
          <p>{{ todo.description }}</p>
        </div>
        <router-link
          class="v2-primary-link"
          :to="todo.targetRoute"
        >
          去处理
        </router-link>
      </article>
    </div>
    <div
      v-else
      class="v2-empty-state"
    >
      <h2>当前没有待办</h2>
      <p>系统处理完成或需要您确认时，事项会显示在这里。</p>
    </div>
  </section>
</template>
