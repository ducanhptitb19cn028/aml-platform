export interface RootCauseEntry {
  component: string
  weight: number
}

export interface Incident {
  incidentId:       string
  detectedAt:       string
  anomalyScore:     number
  affectedServices: string[]
  rootCauseRanking: RootCauseEntry[]
  breachEtaMinutes: number
  confidence:       number
}

export interface RemediationRecord {
  recordId:        string
  decisionId:      string
  incidentId:      string
  action:          string
  targetService:   string
  targetNamespace: string
  status:          'PENDING' | 'EXECUTING' | 'COMPLETED' | 'FAILED' | 'VETOED'
  preState:        string | null
  postState:       string | null
  executedAt:      string
}

export interface IncidentOutcome {
  outcomeId:   string
  incidentId:  string
  decisionId:  string
  label:       'RESOLVED' | 'NO_EFFECT' | 'DEGRADED_FURTHER'
  sloBefore:   number
  sloAfter:    number
  evaluatedAt: string
}

export interface ServiceHealth {
  service:          string
  namespace:        'aml' | 'aiops'
  status:           'OK' | 'WARNING' | 'CRITICAL'
  lastAnomalyScore: number
  checkedAt:        string
}

export interface OutcomeSummary {
  RESOLVED:          number
  NO_EFFECT:         number
  DEGRADED_FURTHER:  number
  [key: string]: number
}

export interface LlmAnalysis {
  analysisId:     string
  incidentId:     string
  analyzedAt:     string
  model:          string
  explanation:    string
  rootCause:      string
  recommendation: string
  amlRisk:        'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  confidence:     number
  cacheHit:       boolean
  trainingSize:   number
}
