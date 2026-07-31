import {
  createRouter,
  createWebHistory,
  type RouteRecordRaw,
  type RouterHistory,
} from 'vue-router'
import { featureFlags, type FeatureFlags } from '../config/featureFlags'

const legacyWorkspace = () => import('../views/WorkspaceView.vue')
const workspaceV2 = () => import('../views/WorkspaceV2View.vue')

export function createAppRouter(
  flags: FeatureFlags = featureFlags,
  history: RouterHistory = createWebHistory(),
) {
  const legacyFallbackRoute: RouteRecordRaw = flags.legacyWorkspaceEnabled
    ? {
        path: '/workspace/legacy',
        name: 'workspace-legacy',
        component: legacyWorkspace,
        meta: { workspaceGeneration: 'legacy', fallback: true },
      }
    : {
        path: '/workspace/legacy',
        redirect: { name: 'workspace' },
      }

  const unavailableV2Redirect = flags.legacyWorkspaceEnabled
    ? '/workspace/legacy'
    : '/workspace'
  const workspaceV2Routes: RouteRecordRaw[] = flags.workspaceV2Enabled
    ? [
        {
          path: '/todos',
          name: 'workspace-todos',
          component: workspaceV2,
          meta: { workspaceGeneration: 'v2' },
        },
        {
          path: '/model-evaluations',
          name: 'model-evaluations',
          component: workspaceV2,
          meta: { workspaceGeneration: 'v2' },
        },
        {
          path: '/projects/:projectKey/:section(overview|idea|direction|evidence|design|protocol|statistics|quality|review|export|models)',
          name: 'workspace-project',
          component: workspaceV2,
          meta: { workspaceGeneration: 'v2' },
        },
      ]
    : [
        { path: '/todos', redirect: unavailableV2Redirect },
        { path: '/model-evaluations', redirect: unavailableV2Redirect },
        {
          path: '/projects/:projectKey/:section(.*)',
          redirect: unavailableV2Redirect,
        },
      ]

  return createRouter({
    history,
    routes: [
      { path: '/', redirect: { name: 'workspace' } },
      {
        path: '/workspace',
        name: 'workspace',
        component: flags.workspaceV2Enabled ? workspaceV2 : legacyWorkspace,
        meta: { workspaceGeneration: flags.workspaceV2Enabled ? 'v2' : 'legacy' },
      },
      ...workspaceV2Routes,
      legacyFallbackRoute,
    ],
  })
}

export const router = createAppRouter()
