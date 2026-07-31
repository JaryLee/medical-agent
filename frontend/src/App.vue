<script setup lang="ts">
import { computed } from 'vue'
import { featureFlags } from './config/featureFlags'
import { useSessionStore } from './stores/session'

const session = useSessionStore()
const canViewModelEvaluations = computed(() =>
  session.user?.roles.some((role) =>
    ['HOSPITAL_ADMIN', 'AUDIT_ADMIN', 'EXPERT'].includes(role),
  ),
)
</script>

<template>
  <main class="shell">
    <nav class="app-nav">
      <router-link to="/workspace">
        {{ featureFlags.workspaceV2Enabled ? '科研课题工作台' : '工程工作台' }}
      </router-link>
      <router-link
        v-if="featureFlags.workspaceV2Enabled && featureFlags.legacyWorkspaceEnabled"
        to="/workspace/legacy"
      >
        旧版工作台
      </router-link>
      <router-link
        v-if="featureFlags.workspaceV2Enabled && canViewModelEvaluations"
        to="/model-evaluations"
      >
        匿名模型评测
      </router-link>
    </nav>
    <router-view />
  </main>
</template>
