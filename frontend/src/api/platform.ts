import axios from 'axios'
import type { ApiResponse } from '../types/research'

const client = axios.create({
  baseURL: '/api',
  timeout: 15_000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

export interface CurrentUser {
  userId: string
  hospitalId?: string
  username: string
  roles: string[]
  forcePasswordChange: boolean
}

export interface Project {
  id: string
  code: string
  name: string
  version: number
}

export type ProjectMemberRole = 'OWNER' | 'EDITOR' | 'VIEWER'

export interface ProjectMember {
  userId: string
  username: string
  role: ProjectMemberRole
}

export interface UploadedProjectFile {
  id: string
  projectId: string
  originalName: string
  contentType: string
  sizeBytes: number
  sha256: string
  securityStatus: 'SAFE' | 'WARNING' | 'BLOCKED_FOR_EXTERNAL_MODEL' | 'REQUIRES_ADMIN_REVIEW'
  matchedRules: string[]
  canSendToExternalModel: boolean
  scanEngine: string
  extractedCharacters: number
  extractionStatus: 'EXTRACTED' | 'EMPTY'
}

export interface AuditEntry {
  id: string
  hospitalId?: string
  actorId?: string
  action: string
  resourceType: string
  resourceId?: string
  occurredAt: string
}

export type ExpertReviewCommentType =
  | 'MEDICAL'
  | 'STATISTICAL'
  | 'REPORTING'
  | 'GENERAL'

export interface ExpertReview {
  reviewTaskId: string
  projectId: string
  agentTaskId: string
  protocolId: string
  strobeCheckTaskId: string
  status:
    | 'WAITING_EXPERT_REVIEW'
    | 'EXPERT_APPROVED'
    | 'REVISION_REQUIRED'
    | 'APPROVED'
  submittedBy: string
  submittedAt: string
  expertReviewerId?: string
  expertDecision?: 'APPROVE' | 'RETURN_FOR_REVISION'
  expertSummary?: string
  expertDecidedAt?: string
  ownerConfirmedBy?: string
  ownerConfirmedAt?: string
  sectionsLocked: boolean
  version: number
  comments: Array<{
    id: string
    protocolSectionId?: string
    protocolSectionVersionNo?: number
    strobeItemResultId?: string
    commentType: ExpertReviewCommentType
    content: string
    createdBy: string
    createdAt: string
  }>
  history: Array<{
    id: string
    actionType: string
    actorUserId?: string
    summary?: string
    occurredAt: string
  }>
}

export interface DocumentTemplate {
  id: string
  templateCode: string
  templateName: string
  versionNo: number
  status: 'VALIDATED' | 'PUBLISHED' | 'ARCHIVED'
  contentSha256: string
  contentSize: number
  placeholderSchemaVersion: string
  placeholders: string[]
  validationStatus: 'VALID' | 'INVALID'
  validationMessage?: string
  createdBy: string
  createdAt: string
  publishedBy?: string
  publishedAt?: string
  version: number
}

export interface CitationStyle {
  id: string
  styleCode: string
  styleName: string
  versionNo: number
  status: 'VALIDATED' | 'PUBLISHED' | 'ARCHIVED'
  layout: 'VANCOUVER' | 'GB_T_7714'
  authorLimit: number
  etAlText: string
  includePmid: boolean
  includeDoi: boolean
  includeEvidenceScope: boolean
  evidenceScopeLabel: string
  createdBy: string
  createdAt: string
  publishedBy?: string
  publishedAt?: string
  version: number
}

export interface DocumentExport {
  id: string
  projectId: string
  agentTaskId: string
  protocolId: string
  reviewTaskId: string
  templateVersionId: string
  citationStyleVersionId: string
  citationStyleCode: string
  citationStyleVersion: string
  status: 'COMPLETED' | 'FAILED'
  requestedBy: string
  confirmedAt: string
  protocolSnapshotSha256: string
  citationSnapshotSha256: string
  citationCount: number
  fileName: string
  contentType: string
  contentSha256: string
  contentSize: number
  completedAt: string
}

export interface AgentTask {
  id: string
  projectId: string
  currentStep: string
  status:
    | 'QUEUED'
    | 'RUNNING'
    | 'WAITING_CONFIRMATION'
    | 'REVISION_REQUIRED'
    | 'COMPLETED'
    | 'FAILED'
    | 'CANCELLED'
  input: {
    idea: string
    clarificationAnswers?: Record<string, string>
    directionId?: string
  }
  output?: {
    clarificationQuestions?: string[]
    directions?: Array<{
      id: string
      title: string
      recommendedStudyType: string
      limitations: string[]
    }>
    peco?: {
      researchQuestion: string
      population: string
      exposure: string
      comparator: string
      outcome: string
    }
    designAssessment?: {
      studyType: string
      requiredFields: string[]
      missingFields: string[]
      readyForDraft: boolean
      explanation: string
      ruleVersion: string
    }
    searchStrategy?: {
      schemaVersion: string
      generatorVersion: string
      queryVersion: string
      confirmationStatus: 'PENDING_CONFIRMATION' | 'CONFIRMED'
      originalResearchQuestion: string
      databases: string[]
      concepts: Array<{
        code: string
        label: string
        terms: string[]
        required: boolean
      }>
      generatedPubmedQuery: string
      pubmedQuery: string
      filters: string[]
      limitations: string[]
    }
    pubmedSearch?: {
      schemaVersion: string
      searchRecordId: string
      database: string
      query: string
      queryVersion: string
      searchedAt: string
      totalResultCount: number
      returnedCount: number
      records: Array<{
        pmid: string
        doi?: string
        title: string
        authors: string[]
        journal: string
        publicationDate: string
        abstractText: string
        evidenceScope: 'ABSTRACT_ONLY' | 'METADATA_ONLY'
        verified: boolean
        source: string
      }>
      rawResponseSha256: string
      rawContentType: string
      toolVersion: string
      externalRequestCount: number
      limitations: string[]
    }
    clinicalTrialsSearch?: {
      schemaVersion: string
      searchRecordId: string
      database: string
      sourceType: 'TRIAL_REGISTRY'
      query: string
      queryVersion: string
      searchedAt: string
      totalResultCount: number
      returnedCount: number
      records: Array<{
        nctId: string
        briefTitle: string
        officialTitle?: string
        overallStatus: string
        studyType: string
        phases: string[]
        conditions: string[]
        interventions: string[]
        briefSummary?: string
        primaryOutcomes: string[]
        leadSponsor?: string
        startDate?: string
        completionDate?: string
        enrollment?: number
        countries: string[]
        hasResults: boolean
        evidenceScope: 'REGISTRY_METADATA_ONLY' | 'REGISTRY_RESULTS_AVAILABLE'
        verified: boolean
        source: string
        linkedPmids: string[]
      }>
      rawResponseSha256: string
      rawContentType: string
      toolVersion: string
      externalRequestCount: number
      dataVersion?: string
      cacheHit: boolean
      limitations: string[]
    }
    literatureValidation?: {
      schemaVersion: string
      validationTaskId: string
      validatedAt: string
      totalCount: number
      verifiedCount: number
      metadataDifferenceCount: number
      mismatchCount: number
      crossrefNotFoundCount: number
      doiNotAvailableCount: number
      citations: Array<{
        pmid: string
        doi?: string
        status:
          | 'VERIFIED'
          | 'VERIFIED_WITH_METADATA_DIFFERENCES'
          | 'MISMATCH'
          | 'CROSSREF_NOT_FOUND'
          | 'DOI_NOT_AVAILABLE'
        validationSource: 'CROSSREF' | 'PUBMED_ONLY'
        fieldChecks: Array<{
          field: string
          status: 'MATCH' | 'MISMATCH' | 'NOT_AVAILABLE'
          pubmedValue?: string
          crossrefValue?: string
        }>
        crossrefMetadata?: {
          doi: string
          title: string
          authors: string[]
          journal?: string
          publicationDate?: string
          type?: string
          publisher?: string
        }
        message: string
      }>
      evidenceLinks: Array<{
        nctId: string
        pmid: string
        relationship: 'REGISTRY_REFERENCES_PUBLICATION'
        status: 'RESOLVED' | 'UNRESOLVED_PUBMED'
      }>
      rawResponseSha256: string
      rawContentType: string
      toolVersion: string
      externalRequestCount: number
      cacheHitCount: number
      limitations: string[]
    }
    similarResearchAnalysis?: {
      schemaVersion: string
      analysisTaskId: string
      analyzedAt: string
      researchQuestion: string
      databaseScope: string[]
      analyzedSourceCount: number
      excludedCitationCount: number
      highSimilarityCount: number
      moderateSimilarityCount: number
      lowSimilarityCount: number
      similarResearch: Array<{
        sourceType: 'PUBMED_ARTICLE' | 'TRIAL_REGISTRY'
        sourceIdentifier: string
        pmid?: string
        doi?: string
        nctId?: string
        title: string
        publicationOrCompletionDate?: string
        similarityScore: number
        similarityTier: 'HIGH' | 'MODERATE' | 'LOW'
        verificationStatus: string
        evidenceScope: string
        dimensions: Array<{
          dimension: 'POPULATION' | 'EXPOSURE' | 'COMPARATOR' | 'OUTCOME' | 'STUDY_DESIGN'
          matched: boolean
          weight: number
          matchedTerms: string[]
        }>
        differences: string[]
        linkedSourceIdentifiers: string[]
      }>
      potentialResearchGaps: Array<{
        code: string
        statement: string
        basis: string
        basisSourceIdentifiers: string[]
      }>
      conclusion: string
      inputSha256: string
      algorithmVersion: string
      limitations: string[]
    }
    observationalDesignRecommendation?: {
      schemaVersion: string
      recommendationTaskId: string
      recommendedAt: string
      recommendedStudyType: 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'
      primaryOutcomeCandidate: string
      alternatives: Array<{
        rank: number
        studyType: 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'
        score: number
        feasibilityStatus: 'READY' | 'NEEDS_CLARIFICATION'
        rationale: string
        requiredFields: string[]
        missingFields: string[]
        biasRisks: string[]
        evidenceConsiderations: string[]
      }>
      readyForProtocolDraft: boolean
      unresolvedItems: string[]
      requiredConfirmations: string[]
      confirmationStatus: 'PENDING_CONFIRMATION' | 'CONFIRMED'
      confirmedStudyType?: 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'
      confirmedPrimaryOutcome?: string
      protocolGenerationAuthorized: boolean
      confirmedBy?: string
      confirmedAt?: string
      inputSha256: string
      algorithmVersion: string
      limitations: string[]
    }
    protocolDraft?: {
      schemaVersion: string
      protocolId: string
      generatedAt: string
      studyType: 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'
      title: string
      sections: Array<{
        sectionId: string
        sectionCode: string
        title: string
        sortOrder: number
        versionNo: number
        content: string
        contentFormat: 'MARKDOWN' | 'PLAIN_TEXT'
        origin: 'AGENT_DETERMINISTIC' | 'AGENT_MODEL' | 'HUMAN'
        evidenceStatus:
          | 'DOCTOR_CONFIRMED_INPUT'
          | 'VERIFIED_METADATA'
          | 'ABSTRACT_ONLY'
          | 'NEEDS_EXPERT_REVIEW'
          | 'NOT_APPLICABLE'
        sourceIdentifiers: string[]
        issuesToConfirm: string[]
      }>
      issuesToConfirm: string[]
      inputSha256: string
      generatorVersion: string
      limitations: string[]
    }
    statisticalAnalysisDraft?: {
      schemaVersion: string
      draftId: string
      protocolId: string
      generatedAt: string
      studyType: 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'
      primaryOutcome: string
      outcomeTypeStatus: 'NEEDS_EXPERT_CONFIRMATION' | 'CONFIRMED'
      descriptiveAnalysis: string[]
      primaryAnalysisCandidates: string[]
      secondaryAnalysis: string[]
      covariates: string[]
      potentialConfounders: string[]
      stratifiedAnalyses: string[]
      subgroupAnalyses: string[]
      sensitivityAnalyses: string[]
      missingDataPlan: string[]
      multipleComparisonPlan: string[]
      modelDiagnostics: string[]
      effectMeasureCandidates: string[]
      confidenceIntervalPlan: string
      sampleSizeParameters: Array<{
        code: string
        label: string
        required: boolean
        valueStatus: 'MISSING_NEEDS_INPUT' | 'PROVIDED_UNVERIFIED' | 'CONFIRMED'
        value?: string
        unit?: string
        rationale: string
      }>
      recommendedSoftware: string[]
      issuesToConfirm: string[]
      statisticalSectionVersion: {
        sectionId: string
        sectionCode: string
        title: string
        sortOrder: number
        versionNo: number
        content: string
        contentFormat: 'MARKDOWN' | 'PLAIN_TEXT'
        origin: 'AGENT_DETERMINISTIC' | 'AGENT_MODEL' | 'HUMAN'
        evidenceStatus:
          | 'DOCTOR_CONFIRMED_INPUT'
          | 'VERIFIED_METADATA'
          | 'ABSTRACT_ONLY'
          | 'NEEDS_EXPERT_REVIEW'
          | 'NOT_APPLICABLE'
        sourceIdentifiers: string[]
        issuesToConfirm: string[]
      }
      inputSha256: string
      generatorVersion: string
      limitations: string[]
    }
    claimCitationValidation?: {
      schemaVersion: string
      validationTaskId: string
      protocolId: string
      validatedAt: string
      claimCount: number
      citationLinkCount: number
      abstractOnlyClaimCount: number
      needsExpertReviewClaimCount: number
      claims: Array<{
        claimId: string
        sectionId: string
        sectionCode: string
        claimOrder: number
        claimType: string
        claimText: string
        supportStatus:
          | 'SUPPORTED'
          | 'PARTIALLY_SUPPORTED'
          | 'NOT_SUPPORTED'
          | 'ABSTRACT_ONLY'
          | 'NEEDS_EXPERT_REVIEW'
        expertConfirmationStatus: 'PENDING_REVIEW' | 'CONFIRMED' | 'REJECTED'
        citationLinks: Array<{
          linkId: string
          claimId: string
          linkOrder: number
          sourceType: 'PUBMED' | 'PMC_FULL_TEXT' | 'CLINICAL_TRIALS_GOV'
          pmid?: string
          doi?: string
          title: string
          supportLevel:
            | 'SUPPORTED'
            | 'PARTIALLY_SUPPORTED'
            | 'NOT_SUPPORTED'
            | 'ABSTRACT_ONLY'
            | 'NEEDS_EXPERT_REVIEW'
          evidenceScope:
            | 'ABSTRACT_ONLY'
            | 'FULL_TEXT'
            | 'REGISTRY_METADATA_ONLY'
            | 'REGISTRY_RESULTS_AVAILABLE'
            | 'TITLE_ONLY'
          evidenceExcerpt: string
          excerptLocation: string
          excerptSha256: string
          citationValidationStatus: string
          manualConfirmationStatus: 'PENDING_REVIEW' | 'CONFIRMED' | 'REJECTED'
        }>
        issuesToConfirm: string[]
      }>
      inputSha256: string
      validatorVersion: string
      limitations: string[]
    }
    strobeCompletenessCheck?: {
      schemaVersion: string
      checkTaskId: string
      protocolId: string
      checkedAt: string
      guidelineCode: 'STROBE'
      guidelineVersion: string
      studyType: 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'
      totalItemCount: number
      coveredCount: number
      partiallyCoveredCount: number
      missingCount: number
      notApplicableCount: number
      needsExpertReviewCount: number
      items: Array<{
        itemResultId: string
        itemCode: string
        sectionGroup: string
        requirementSummary: string
        studyType: 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'
        status:
          | 'COVERED'
          | 'PARTIALLY_COVERED'
          | 'MISSING'
          | 'NOT_APPLICABLE'
          | 'NEEDS_EXPERT_REVIEW'
        mappedSectionCodes: string[]
        evidenceSnippets: string[]
        message: string
        suggestion: string
        requiresExpertReview: boolean
      }>
      inputSha256: string
      checkerVersion: string
      sourceReference: string
      automaticPrecheckDisclaimer: string
      limitations: string[]
    }
    expertReview?: ExpertReview
    documentExport?: {
      schemaVersion: string
      exportId: string
      templateVersionId: string
      templateCode: string
      templateVersionNo: number
      citationStyleVersionId: string
      citationStyleCode: string
      citationStyleVersion: string
      citationLayout: 'VANCOUVER' | 'GB_T_7714'
      citationCount: number
      contentSha256: string
      contentSize: number
      fileName: string
      completedAt: string
    }
  }
  version: number
  errorCode?: string
  errorMessage?: string
  createdAt: string
}

export interface AgentClarificationRound {
  id: string
  roundNo: number
  sourceStep: string
  questions: string[]
  answers: Record<string, string>
  submittedBy: string
  submittedAt: string
}

export async function login(hospitalCode: string, username: string, password: string) {
  const response = await client.post<ApiResponse<CurrentUser>>('/auth/login', {
    hospitalCode: hospitalCode || undefined,
    username,
    password,
  })
  await client.get('/auth/csrf')
  return response.data.data
}

export async function currentUser() {
  const response = await client.get<ApiResponse<CurrentUser>>('/auth/me')
  return response.data.data
}

export async function changePassword(currentPassword: string, newPassword: string) {
  await client.post('/auth/change-password', { currentPassword, newPassword })
}

export async function listProjects() {
  const response = await client.get<ApiResponse<Project[]>>('/research/projects')
  return response.data.data
}

export async function createProject(code: string, name: string) {
  const response = await client.post<ApiResponse<Project>>(
    '/research/projects',
    { code, name },
    { headers: { 'Idempotency-Key': crypto.randomUUID() } },
  )
  return response.data.data
}

export async function listProjectMembers(projectId: string) {
  const response = await client.get<ApiResponse<ProjectMember[]>>(
    `/research/projects/${projectId}/members`,
  )
  return response.data.data
}

export async function addProjectMember(
  projectId: string,
  userId: string,
  role: ProjectMemberRole,
) {
  const response = await client.post<ApiResponse<ProjectMember>>(
    `/research/projects/${projectId}/members`,
    { userId, role },
  )
  return response.data.data
}

export async function uploadProjectFile(projectId: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  const response = await client.post<ApiResponse<UploadedProjectFile>>(
    `/research/projects/${projectId}/files`,
    form,
  )
  return response.data.data
}

export async function listAudits(limit = 100) {
  const response = await client.get<ApiResponse<AuditEntry[]>>('/audits', { params: { limit } })
  return response.data.data
}

export async function createAgentTask(projectId: string, idea: string) {
  const response = await client.post<ApiResponse<AgentTask>>(
    '/agent/tasks',
    { projectId, idea },
    { headers: { 'Idempotency-Key': crypto.randomUUID() } },
  )
  return response.data.data
}

export async function getAgentTask(taskId: string) {
  const response = await client.get<ApiResponse<AgentTask>>(`/agent/tasks/${taskId}`)
  return response.data.data
}

export async function listAgentTasks(projectId: string) {
  const response = await client.get<ApiResponse<AgentTask[]>>('/agent/tasks', {
    params: { projectId },
  })
  return response.data.data
}

export async function confirmAgentDirection(taskId: string, directionId: string) {
  const response = await client.post<ApiResponse<AgentTask>>(
    `/agent/tasks/${taskId}/confirm-direction`,
    { directionId },
  )
  return response.data.data
}

export async function confirmAgentSearchStrategy(taskId: string, pubmedQuery: string) {
  const response = await client.post<ApiResponse<AgentTask>>(
    `/agent/tasks/${taskId}/confirm-search-strategy`,
    { pubmedQuery },
  )
  return response.data.data
}

export async function confirmAgentObservationalDesign(
  taskId: string,
  studyType: 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL',
  primaryOutcome: string,
  authorizeProtocolGeneration: boolean,
) {
  const response = await client.post<ApiResponse<AgentTask>>(
    `/agent/tasks/${taskId}/confirm-observational-design`,
    { studyType, primaryOutcome, authorizeProtocolGeneration },
  )
  return response.data.data
}

export async function submitAgentClarifications(
  taskId: string,
  answers: Record<string, string>,
) {
  const response = await client.post<ApiResponse<AgentTask>>(
    `/agent/tasks/${taskId}/clarifications`,
    { answers },
  )
  return response.data.data
}

export async function listAgentClarifications(taskId: string) {
  const response = await client.get<ApiResponse<AgentClarificationRound[]>>(
    `/agent/tasks/${taskId}/clarifications`,
  )
  return response.data.data
}

export async function getExpertReview(taskId: string) {
  const response = await client.get<ApiResponse<ExpertReview>>(
    `/agent/tasks/${taskId}/expert-review`,
  )
  return response.data.data
}

export async function addExpertReviewComment(
  taskId: string,
  request: {
    protocolSectionId?: string
    protocolSectionVersionNo?: number
    strobeItemResultId?: string
    commentType: ExpertReviewCommentType
    content: string
  },
) {
  const response = await client.post<ApiResponse<ExpertReview>>(
    `/agent/tasks/${taskId}/expert-review/comments`,
    request,
  )
  return response.data.data
}

export async function submitExpertReviewDecision(
  taskId: string,
  decision: 'APPROVE' | 'RETURN_FOR_REVISION',
  summary: string,
  expectedVersion: number,
) {
  const response = await client.post<ApiResponse<ExpertReview>>(
    `/agent/tasks/${taskId}/expert-review/decision`,
    { decision, summary, expectedVersion },
  )
  return response.data.data
}

export async function confirmExpertReviewByOwner(
  taskId: string,
  expectedVersion: number,
) {
  const response = await client.post<ApiResponse<ExpertReview>>(
    `/agent/tasks/${taskId}/expert-review/owner-confirmation`,
    { expectedVersion },
  )
  return response.data.data
}

export async function listDocumentTemplates() {
  const response = await client.get<ApiResponse<DocumentTemplate[]>>('/document-templates')
  return response.data.data
}

export async function installDefaultDocumentTemplate() {
  const response = await client.post<ApiResponse<DocumentTemplate>>(
    '/document-templates/default',
  )
  return response.data.data
}

export async function uploadDocumentTemplate(
  templateCode: string,
  templateName: string,
  file: File,
) {
  const form = new FormData()
  form.append('templateCode', templateCode)
  form.append('templateName', templateName)
  form.append('file', file)
  const response = await client.post<ApiResponse<DocumentTemplate>>(
    '/document-templates',
    form,
  )
  return response.data.data
}

export async function publishDocumentTemplate(templateId: string, expectedVersion: number) {
  const response = await client.post<ApiResponse<DocumentTemplate>>(
    `/document-templates/${templateId}/publish`,
    { expectedVersion },
  )
  return response.data.data
}

export async function previewDocumentTemplate(templateId: string) {
  const response = await client.post<Blob>(
    `/document-templates/${templateId}/preview`,
    undefined,
    { responseType: 'blob' },
  )
  return response.data
}

export async function listCitationStyles() {
  const response = await client.get<ApiResponse<CitationStyle[]>>('/citation-styles')
  return response.data.data
}

export async function installDefaultCitationStyle() {
  const response = await client.post<ApiResponse<CitationStyle>>(
    '/citation-styles/default',
  )
  return response.data.data
}

export async function createCitationStyle(input: {
  styleCode: string
  styleName: string
  layout: 'VANCOUVER' | 'GB_T_7714'
  authorLimit: number
  etAlText: string
  includeDoi: boolean
  includeEvidenceScope: boolean
  evidenceScopeLabel: string
}) {
  const response = await client.post<ApiResponse<CitationStyle>>(
    '/citation-styles',
    input,
  )
  return response.data.data
}

export async function publishCitationStyle(styleId: string, expectedVersion: number) {
  const response = await client.post<ApiResponse<CitationStyle>>(
    `/citation-styles/${styleId}/publish`,
    { expectedVersion },
  )
  return response.data.data
}

export async function getDocumentExport(taskId: string) {
  const response = await client.get<ApiResponse<DocumentExport>>(
    `/agent/tasks/${taskId}/document-export`,
  )
  return response.data.data
}

export async function confirmDocumentExport(
  taskId: string,
  templateVersionId: string,
  citationStyleVersionId: string,
  confirmReviewedContent: boolean,
) {
  const response = await client.post<ApiResponse<DocumentExport>>(
    `/agent/tasks/${taskId}/document-export`,
    { templateVersionId, citationStyleVersionId, confirmReviewedContent },
  )
  return response.data.data
}

export function documentExportDownloadUrl(exportId: string) {
  return `/api/document-exports/${exportId}/download`
}

export async function cancelAgentTask(taskId: string) {
  const response = await client.post<ApiResponse<AgentTask>>(`/agent/tasks/${taskId}/cancel`)
  return response.data.data
}

export async function retryAgentTask(taskId: string) {
  const response = await client.post<ApiResponse<AgentTask>>(`/agent/tasks/${taskId}/retry`)
  return response.data.data
}
