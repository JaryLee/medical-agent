import axios from 'axios'
import type { AnalysisResult, ApiResponse, PrototypeResult } from '../types/research'

const client = axios.create({
  baseURL: '/api/prototype',
  timeout: 15_000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

export async function analyzeIdea(idea: string): Promise<AnalysisResult> {
  const response = await client.post<ApiResponse<AnalysisResult>>('/ideas/analyze', { idea })
  return response.data.data
}

export async function confirmDirection(idea: string, directionId: string): Promise<PrototypeResult> {
  const response = await client.post<ApiResponse<PrototypeResult>>('/directions/confirm', {
    idea,
    directionId,
  })
  return response.data.data
}
