import axios, { AxiosError } from 'axios'
import type {
  ArtifactSectionView,
  IdeaDirectionView,
  ProjectEvent,
  StageView,
  TodoItem,
  WorkspaceEnvelope,
  WorkspacePage,
  WorkspaceSummary,
  ProtocolModelCandidate,
  ProtocolModelReview,
  ObservationalDesignAdvice,
  ModelUsageView,
  ModelGovernanceView,
  ModelEvaluation,
} from '../types/workspace'
import { WorkspaceApiError } from '../types/workspace'

const client = axios.create({
  baseURL: '/api/research',
  timeout: 15_000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

interface FailureBody {
  error?: {
    code?: string
    message?: string
  }
}

function safeError(error: unknown): never {
  if (error instanceof WorkspaceApiError) throw error
  if (error instanceof AxiosError) {
    const body = error.response?.data as FailureBody | undefined
    const code = body?.error?.code ?? 'WORKSPACE_REQUEST_FAILED'
    const message = body?.error?.message
      ?? (error.response?.status === 401
        ? '登录状态已失效，请重新登录。'
        : '课题工作台暂时不可用，请稍后重试。')
    throw new WorkspaceApiError(code, message, error.response?.status)
  }
  throw new WorkspaceApiError(
    'WORKSPACE_REQUEST_FAILED',
    '课题工作台暂时不可用，请稍后重试。',
  )
}

async function request<T>(operation: () => Promise<{ data: T }>): Promise<T> {
  try {
    return (await operation()).data
  } catch (error) {
    return safeError(error)
  }
}

export function listWorkspaceProjects(limit = 50, cursor?: string) {
  return request<WorkspaceEnvelope<WorkspacePage<WorkspaceSummary>>>(() =>
    client.get('/workspace/projects', { params: { limit, cursor } }),
  )
}

export function getWorkspaceSummary(projectKey: string) {
  return request<WorkspaceEnvelope<WorkspaceSummary>>(() =>
    client.get(`/projects/${encodeURIComponent(projectKey)}/workspace-summary`),
  )
}

export function getWorkspaceStages(projectKey: string) {
  return request<WorkspaceEnvelope<StageView[]>>(() =>
    client.get(`/projects/${encodeURIComponent(projectKey)}/stages`),
  )
}

export function listWorkspaceTodos(limit = 50, cursor?: string) {
  return request<WorkspaceEnvelope<WorkspacePage<TodoItem>>>(() =>
    client.get('/todos', { params: { status: 'OPEN', limit, cursor } }),
  )
}

export function getProjectTodos(projectKey: string) {
  return request<WorkspaceEnvelope<TodoItem[]>>(() =>
    client.get(`/projects/${encodeURIComponent(projectKey)}/todos`, {
      params: { status: 'OPEN' },
    }),
  )
}

export function getIdeaDirection(projectKey: string) {
  return request<WorkspaceEnvelope<IdeaDirectionView>>(() =>
    client.get(`/projects/${encodeURIComponent(projectKey)}/idea-direction`),
  )
}

const artifactPaths = {
  evidence: 'evidence',
  design: 'design',
  protocol: 'protocol',
  statistics: 'statistics',
  quality: 'quality',
  review: 'internal-review',
  export: 'draft-export',
} as const

export type ArtifactSection = keyof typeof artifactPaths

export function getWorkspaceArtifact(
  projectKey: string,
  section: ArtifactSection,
) {
  return request<WorkspaceEnvelope<ArtifactSectionView>>(() =>
    client.get(
      `/projects/${encodeURIComponent(projectKey)}/${artifactPaths[section]}`,
    ),
  )
}

export function executeWorkspaceAction(
  projectKey: string,
  actionCode: string,
  readModelVersion: number,
  idempotencyKey: string,
  body: unknown,
) {
  return request<WorkspaceEnvelope<WorkspaceSummary>>(() =>
    client.post(
      `/projects/${encodeURIComponent(projectKey)}/actions/${encodeURIComponent(actionCode)}`,
      body,
      {
        headers: {
          'Idempotency-Key': idempotencyKey,
          'If-Match': `"rmv-${readModelVersion}"`,
        },
      },
    ),
  )
}

export function getProtocolModelCandidates(projectKey: string) {
  return request<WorkspaceEnvelope<ProtocolModelCandidate[]>>(() =>
    client.get(
      `/projects/${encodeURIComponent(projectKey)}/protocol/model-candidates`,
    ),
  )
}

export function getProtocolModelReviews(projectKey: string) {
  return request<WorkspaceEnvelope<ProtocolModelReview[]>>(() =>
    client.get(
      `/projects/${encodeURIComponent(projectKey)}/protocol/model-reviews`,
    ),
  )
}

export function getObservationalDesignAdvice(projectKey: string) {
  return request<WorkspaceEnvelope<ObservationalDesignAdvice[]>>(() =>
    client.get(
      `/projects/${encodeURIComponent(projectKey)}/design/model-advice`,
    ),
  )
}

export function getModelUsage(projectKey: string) {
  return request<WorkspaceEnvelope<ModelUsageView>>(() =>
    client.get(`/projects/${encodeURIComponent(projectKey)}/model-usage`),
  )
}

export function getModelGovernance(projectKey: string) {
  return request<WorkspaceEnvelope<ModelGovernanceView>>(() =>
    client.get(`/projects/${encodeURIComponent(projectKey)}/model-governance`),
  )
}

export function updateModelBudget(
  projectKey: string,
  body: {
    expectedVersion: number
    maxCallCostMicros: number
    maxProjectCostMicros: number
    status: 'ACTIVE' | 'DISABLED'
  },
) {
  return request<WorkspaceEnvelope<ModelGovernanceView>>(() =>
    client.put(
      `/projects/${encodeURIComponent(projectKey)}/model-governance/budget`,
      body,
    ),
  )
}

export function listModelEvaluations() {
  return request<WorkspaceEnvelope<ModelEvaluation[]>>(() =>
    client.get('/model-evaluations'),
  )
}

export function startModelEvaluation() {
  const idempotencyKey = crypto.randomUUID()
  return request<WorkspaceEnvelope<ModelEvaluation>>(() =>
    client.post('/model-evaluations', undefined, {
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  )
}

export function submitModelEvaluationScore(
  evaluationKey: string,
  body: {
    responsibility: 'MEDICAL_REVIEW' | 'STATISTICAL_REVIEW'
    correctnessScore: number
    completenessScore: number
    safetyScore: number
    actionabilityScore: number
    recommendation: 'ACCEPT' | 'REVISE' | 'REJECT'
    comment: string
  },
) {
  const idempotencyKey = crypto.randomUUID()
  return request<WorkspaceEnvelope<ModelEvaluation>>(() =>
    client.post(
      `/model-evaluations/${encodeURIComponent(evaluationKey)}/expert-scores`,
      body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    ),
  )
}

export interface WorkspaceEventSubscription {
  close(): void
}

export function subscribeToProjectEvents(
  projectKey: string,
  onEvent: (event: ProjectEvent) => void,
  onConnectionChange: (connected: boolean) => void,
): WorkspaceEventSubscription {
  const source = new EventSource(
    `/api/research/projects/${encodeURIComponent(projectKey)}/events`,
    { withCredentials: true },
  )
  const handle = (raw: MessageEvent<string>) => {
    try {
      const event = JSON.parse(raw.data) as ProjectEvent
      if (event.projectKey === projectKey) onEvent(event)
    } catch {
      // Ignore malformed notifications. The next valid version signal
      // or a manual refresh restores the read model.
    }
  }
  source.addEventListener('PROJECT_READ_MODEL_CHANGED', handle as EventListener)
  source.addEventListener('PROJECT_RESYNC_REQUIRED', handle as EventListener)
  source.onopen = () => onConnectionChange(true)
  source.onerror = () => onConnectionChange(false)
  return { close: () => source.close() }
}

export function createIdempotencyKey(actionCode: string): string {
  const bytes = new Uint32Array(3)
  globalThis.crypto.getRandomValues(bytes)
  return [
    'workspace',
    actionCode.toLowerCase(),
    Date.now().toString(36),
    ...Array.from(bytes, (value) => value.toString(36)),
  ].join('-').slice(0, 128)
}
