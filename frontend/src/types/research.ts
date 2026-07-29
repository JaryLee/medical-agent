export type StudyType = 'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'

export interface ResearchDirection {
  id: string
  title: string
  recommendedStudyType: StudyType
  researchPurpose: string
  limitations: string[]
}

export interface AnalysisResult {
  clarificationQuestions: string[]
  directions: ResearchDirection[]
  disclaimer: string
}

export interface PrototypeResult {
  peco: {
    population: string
    exposure: string
    comparator: string
    outcome: string
    researchQuestion: string
  }
  literature: Array<{
    citationId: string
    pmid: string
    title: string
    evidenceScope: string
  }>
  background: string
  evidenceDisclaimer: string
}

export interface ApiResponse<T> {
  success: boolean
  data: T
  error?: { code: string; message: string; traceId: string }
  traceId: string
}
