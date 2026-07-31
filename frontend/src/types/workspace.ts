export interface LabeledCode {
  code: string
  label: string
}

export interface WorkspaceProgress {
  completed: number
  total: number
  percent: number
}

export interface AllowedAction {
  code: string
  label: string
  enabled: boolean
  reasonCode?: string
  reason?: string
  targetRoute: string
}

export interface BlockedReason {
  code: string
  message: string
}

export interface StageView {
  code: string
  label: string
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'WAITING_USER' | 'BLOCKED' | 'FAILED' | 'COMPLETED'
  summary: string
  targetRoute: string
  blockedReasonCodes: string[]
  completedAt?: string
}

export interface WorkspaceSummary {
  projectKey: string
  displayName: string
  businessStatus: LabeledCode
  currentStage: StageView
  progress: WorkspaceProgress
  nextAction: AllowedAction
  allowedActions: AllowedAction[]
  blockedReasons: BlockedReason[]
  pendingTodoCount: number
  lastUpdatedAt: string
}

export interface TodoItem {
  todoKey: string
  projectKey: string
  todoType: LabeledCode
  title: string
  description: string
  assigneeRole: string
  targetRoute: string
  dueAt?: string
  status: 'OPEN'
}

export interface ResponseMeta {
  readModelVersion: number
  asOf: string
  latestEventId: number
}

export interface WorkspaceEnvelope<T> {
  data: T
  meta: ResponseMeta
}

export interface WorkspacePage<T> {
  items: T[]
  nextCursor?: string
}

export interface ResearchIdea {
  content: string
  statusLabel: string
}

export interface ClarificationRound {
  roundNo: number
  questions: string[]
  answers: Record<string, string>
  submittedAt: string
}

export interface DirectionCandidate {
  directionKey: string
  title: string
  recommendedStudyType: LabeledCode
  limitations: string[]
  selected: boolean
}

export interface DirectionCandidateSet {
  candidateSetKey: string
  schemaVersion: string
  candidates: DirectionCandidate[]
}

export interface IdeaDirectionView {
  projectKey: string
  workflowStatus: LabeledCode
  idea?: ResearchIdea
  currentClarificationQuestions: string[]
  clarificationHistory: ClarificationRound[]
  directionCandidates?: DirectionCandidateSet
  allowedActions: AllowedAction[]
  disclaimer: string
}

export interface ArtifactSectionView {
  projectKey: string
  sectionCode: string
  title: string
  status: LabeledCode
  content: Record<string, unknown>
  allowedActions: AllowedAction[]
  disclaimer: string
}

export interface ProjectEvent {
  eventId: number
  type: 'PROJECT_READ_MODEL_CHANGED' | 'PROJECT_RESYNC_REQUIRED'
  projectKey: string
  readModelVersion: number
  occurredAt: string
}

export interface ProtocolModelCandidate {
  candidateKey: string
  sectionKey: string
  sectionCode: string
  baseVersionNo: number
  status: 'VALIDATED' | 'REJECTED' | 'APPLIED' | 'SUPERSEDED'
  content: string
  usedEvidenceIdentifiers: string[]
  issuesToConfirm: string[]
  generatedAt: string
  version: number
  appliedVersionNo?: number
}

export interface ProtocolModelReviewIssue {
  type: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'BLOCKING'
  location: string
  message: string
  suggestedChange: string
}

export interface ProtocolModelReview {
  reviewKey: string
  candidateKey: string
  severity: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH' | 'BLOCKING'
  issues: ProtocolModelReviewIssue[]
  summary: string
  advisoryOnly: true
  createdAt: string
}

export interface ObservationalDesignAdvice {
  adviceKey: string
  ruleVersion: string
  ruleRecommendedStudyType: string
  modelSelectedStudyType: string
  advice: {
    schemaVersion: string
    selectedStudyType: string
    alignment: string
    rationale: string
    biasConsiderations: string[]
    missingFields: string[]
    suggestedConfirmations: string[]
    limitations: string[]
    advisoryOnly: true
  }
  conflicts: string[]
  status: 'ALIGNED' | 'CONFLICT'
  advisoryOnly: true
  createdAt: string
}

export interface ModelCallUsage {
  callKey: string
  logicalModelTypeLabel: string
  provider: string
  modelName: string
  status: string
  statusLabel: string
  usageSource: string
  inputTokens?: number
  cachedInputTokens?: number
  outputTokens?: number
  totalTokens?: number
  priceVersion?: string
  priceCurrency?: string
  reservedCostMicros?: number
  estimatedCostMicros?: number
  costStatus: string
  costStatusLabel: string
  startedAt: string
  completedAt?: string
}

export interface ModelUsageView {
  callCount: number
  succeededCostMicros: number
  activeReservationCostMicros: number
  committedOrReservedCostMicros: number
  calls: ModelCallUsage[]
  disclaimer: string
}

export interface ModelGovernanceView {
  configuredMode: string
  externalModelEnabled: boolean
  externalModelOffByDefault: boolean
  routes: Array<{
    logicalModelTypeLabel: string
    provider: string
    modelName: string
    policyVersion: string
    routeReason: string
    priced: boolean
    priceVersion?: string
    priceCurrency?: string
  }>
  budget: {
    currency: string
    maxCallCostMicros: number
    maxProjectCostMicros: number
    status: 'ACTIVE' | 'DISABLED'
    version: number
    persisted: boolean
    committedOrReservedCostMicros: number
    activeReservationCostMicros: number
    remainingCostMicros: number
  }
  budgetPolicy: string
  disclaimer: string
}

export interface ModelEvaluation {
  evaluationKey: string
  datasetVersion: string
  dataClassification: 'SYNTHETIC_ANONYMOUS'
  promptVersion: string
  routePolicyVersion: string
  status: 'RUNNING' | 'WAITING_EXPERT_SCORING' | 'COMPLETED' | 'FAILED'
  statusLabel: string
  caseCount: number
  passedCount?: number
  cases: Array<{
    caseKey: string
    logicalModelType: string
    provider: string
    modelName: string
    passed: boolean
    metrics: Record<string, unknown>
    errorCode?: string
    evaluatedAt: string
  }>
  expertScores: Array<{
    responsibility: string
    responsibilityLabel: string
    correctnessScore: number
    completenessScore: number
    safetyScore: number
    actionabilityScore: number
    recommendation: string
    comment: string
    submittedAt: string
  }>
  expertScoringRequired: boolean
  disclaimer: string
  startedAt: string
  automatedCompletedAt?: string
}

export type WorkspaceConnectionState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'RECONNECTING'

export class WorkspaceApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status?: number,
  ) {
    super(message)
    this.name = 'WorkspaceApiError'
  }
}
