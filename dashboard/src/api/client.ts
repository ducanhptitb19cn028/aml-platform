import type {
  Incident,
  RemediationRecord,
  IncidentOutcome,
  ServiceHealth,
  OutcomeSummary,
} from '../types'

const BASE = '/api/v1'

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${path}`)
  return res.json() as Promise<T>
}

export const api = {
  incidents:      () => get<Incident[]>('/incidents'),
  remediations:   () => get<RemediationRecord[]>('/remediations'),
  outcomes:       () => get<IncidentOutcome[]>('/outcomes'),
  serviceHealth:  () => get<ServiceHealth[]>('/services/health'),
  outcomeSummary: () => get<OutcomeSummary>('/outcomes/summary'),
}

const LLM_BASE = '/api/llm/v1'
async function llmGet<T>(path: string): Promise<T> {
  const res = await fetch(`${LLM_BASE}${path}`)
  if (!res.ok) throw new Error(`${res.status} — ${path}`)
  return res.json() as Promise<T>
}

export const llmApi = {
  analyses: (limit = 10) => llmGet<import('../types').LlmAnalysis[]>(`/analyses?limit=${limit}`),
  stats:    ()           => llmGet<{ trainingExamples: number; totalAnalyses: number; cacheEnabled: boolean }>('/stats'),
}
