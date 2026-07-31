import { defineStore } from 'pinia'
import {
  createIdempotencyKey,
  executeWorkspaceAction,
  getIdeaDirection,
  getProjectTodos,
  getWorkspaceArtifact,
  getWorkspaceStages,
  getWorkspaceSummary,
  listWorkspaceProjects,
  listWorkspaceTodos,
  subscribeToProjectEvents,
  type WorkspaceEventSubscription,
  type ArtifactSection,
} from '../api/workspaceV2'
import { shouldRefreshForEvent } from '../features/workspace/workspaceState'
import type {
  ArtifactSectionView,
  IdeaDirectionView,
  StageView,
  TodoItem,
  WorkspaceConnectionState,
  WorkspaceSummary,
} from '../types/workspace'
import { WorkspaceApiError } from '../types/workspace'

let eventSubscription: WorkspaceEventSubscription | undefined
let refreshPending = false

export const useWorkspaceV2Store = defineStore('workspaceV2', {
  state: () => ({
    projects: [] as WorkspaceSummary[],
    todos: [] as TodoItem[],
    currentSummary: undefined as WorkspaceSummary | undefined,
    currentStages: [] as StageView[],
    currentTodos: [] as TodoItem[],
    ideaDirection: undefined as IdeaDirectionView | undefined,
    currentArtifact: undefined as ArtifactSectionView | undefined,
    currentVersion: 0,
    latestEventId: 0,
    loading: false,
    actionPending: false,
    errorMessage: '',
    errorCode: '',
    connection: 'DISCONNECTED' as WorkspaceConnectionState,
  }),
  actions: {
    clearError() {
      this.errorMessage = ''
      this.errorCode = ''
    },
    captureError(error: unknown) {
      if (error instanceof WorkspaceApiError) {
        this.errorCode = error.code
        this.errorMessage = error.message
      } else {
        this.errorCode = 'WORKSPACE_REQUEST_FAILED'
        this.errorMessage = '课题工作台暂时不可用，请稍后重试。'
      }
    },
    async loadDashboard() {
      this.loading = true
      this.clearError()
      try {
        const [projects, todos] = await Promise.all([
          listWorkspaceProjects(),
          listWorkspaceTodos(),
        ])
        this.projects = projects.data.items
        this.todos = todos.data.items
      } catch (error) {
        this.captureError(error)
      } finally {
        this.loading = false
      }
    },
    async loadProject(projectKey: string, section = 'overview') {
      this.loading = true
      this.clearError()
      if (this.currentSummary?.projectKey !== projectKey) {
        this.currentSummary = undefined
        this.currentStages = []
        this.currentTodos = []
        this.ideaDirection = undefined
        this.currentArtifact = undefined
        this.currentVersion = 0
        this.latestEventId = 0
      }
      try {
        const requests = [
          getWorkspaceSummary(projectKey),
          getWorkspaceStages(projectKey),
          getProjectTodos(projectKey),
        ] as const
        const [summary, stages, todos] = await Promise.all(requests)
        this.currentSummary = summary.data
        this.currentStages = stages.data
        this.currentTodos = todos.data
        this.currentVersion = summary.meta.readModelVersion
        this.latestEventId = summary.meta.latestEventId
        if (['idea', 'direction'].includes(section)) {
          const idea = await getIdeaDirection(projectKey)
          this.ideaDirection = idea.data
          this.currentVersion = Math.max(
            this.currentVersion,
            idea.meta.readModelVersion,
          )
          this.latestEventId = Math.max(
            this.latestEventId,
            idea.meta.latestEventId,
          )
        } else {
          this.ideaDirection = undefined
        }
        if (isArtifactSection(section)) {
          const artifact = await getWorkspaceArtifact(
            projectKey,
            section as ArtifactSection,
          )
          this.currentArtifact = artifact.data
          this.currentVersion = Math.max(
            this.currentVersion,
            artifact.meta.readModelVersion,
          )
          this.latestEventId = Math.max(
            this.latestEventId,
            artifact.meta.latestEventId,
          )
        } else {
          this.currentArtifact = undefined
        }
      } catch (error) {
        this.captureError(error)
      } finally {
        this.loading = false
      }
    },
    async refreshCurrent(section = 'overview') {
      const projectKey = this.currentSummary?.projectKey
      if (projectKey) await this.loadProject(projectKey, section)
    },
    async runAction(
      projectKey: string,
      actionCode: string,
      body: unknown,
      section = 'overview',
    ): Promise<boolean> {
      this.actionPending = true
      this.clearError()
      try {
        const response = await executeWorkspaceAction(
          projectKey,
          actionCode,
          this.currentVersion,
          createIdempotencyKey(actionCode),
          body,
        )
        this.currentSummary = response.data
        this.currentVersion = response.meta.readModelVersion
        this.latestEventId = response.meta.latestEventId
        await this.loadProject(projectKey, section)
        return true
      } catch (error) {
        this.captureError(error)
        if (
          error instanceof WorkspaceApiError
          && error.code === 'READ_MODEL_VERSION_CONFLICT'
        ) {
          await this.loadProject(projectKey, section)
        }
        return false
      } finally {
        this.actionPending = false
      }
    },
    connect(projectKey: string, section = 'overview') {
      eventSubscription?.close()
      this.connection = 'CONNECTING'
      eventSubscription = subscribeToProjectEvents(
        projectKey,
        (event) => {
          if (!shouldRefreshForEvent(this.currentVersion, event)) return
          if (refreshPending) return
          refreshPending = true
          void this.loadProject(projectKey, section).finally(() => {
            refreshPending = false
          })
        },
        (connected) => {
          this.connection = connected
            ? 'CONNECTED'
            : this.connection === 'CONNECTING'
              ? 'RECONNECTING'
              : 'DISCONNECTED'
        },
      )
    },
    disconnect() {
      eventSubscription?.close()
      eventSubscription = undefined
      refreshPending = false
      this.connection = 'DISCONNECTED'
    },
    reset() {
      this.disconnect()
      this.$reset()
    },
  },
})

function isArtifactSection(value: string): value is ArtifactSection {
  return [
    'evidence',
    'design',
    'protocol',
    'statistics',
    'quality',
    'review',
    'export',
  ].includes(value)
}
