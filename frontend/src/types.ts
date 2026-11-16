export type IncidentStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED'
export type Severity = 'P0' | 'P1' | 'P2' | 'P3'
export type TriageRoute = 'deep_gemma4_rca' | 'fast_rag_only' | 'skip_llm_noise'
export type RemediationSafety = 'safe_auto_rollback' | 'requires_human_approval' | 'escalate_dba_finance'

export interface DecisionGateCriteria {
  noiseGatePassed?: boolean
  triageRoute?: string
  groundingConfidence?: number
  isActionableNoul?: number
  isActionableConfidence?: number
  rawTriageRouteChoice?: string
  commitCausedBugNoul?: number
  runbookAppliesNoul?: number
  severityScore?: number
  severityConfidence?: number
}

export interface DecisionGateAnswer {
  type?: string
  noul?: number
  choice?: string
  score?: number
  confidence?: number
  probabilities?: Record<string, number>
}

export interface DecisionGateRemediationSafety {
  type?: string
  choice?: RemediationSafety | string
  confidence?: number
  probabilities?: Record<string, number>
}

export interface DecisionGateMetadata {
  status?: string
  decision?: string
  decisionType?: string
  triageRoute?: TriageRoute | string
  calibratedSeverity?: Severity | string
  calibratedConfidence?: number
  gitDiffIncluded?: boolean
  runbookContextIncluded?: boolean
  noiseGated?: boolean
  decisionLatencyMs?: number
  criteria?: DecisionGateCriteria
  reasoning?: string
  answers?: Record<string, DecisionGateAnswer>
  remediationSafety?: DecisionGateRemediationSafety
  usage?: Record<string, unknown>
  evaluatedAt?: string
}

export interface AiRcaReport {
  rootCauseSummary: string
  suspectedCommit: string
  matchedRunbook: string
  confidenceScore: number
  severity: Severity
  suggestedRemediation: string
}

export interface Incident {
  id: string
  tenantId: string
  fingerprintHash: string
  serviceName: string
  rawStackTrace: string
  sanitizedStackTrace: string
  occurrenceCount: number
  status: IncidentStatus
  aiRcaJson?: AiRcaReport | null
  firstSeen: string
  lastSeen: string
  decisionGateJson?: DecisionGateMetadata | null
}

export interface Telemetry {
  tenantId: string
  totalLogs: number
  deduplicatedLogs: number
  uniqueIncidents: number
  deduplicationRatio: number
  estimatedCostSavedUsd: number
  decisionGatedLogs?: number
  totalDecisions?: number
  averageDecisionLatencyMs?: number
}

export interface Tenant {
  id: string
  name: string
}

export interface ResolveIncidentRequest {
  verifiedRootCause: string
  verifiedFixCommandOrPR: string
  resolvedBy: string
}

export interface SimulateOutageRequest {
  tenantId: string
  scenario: string
  logCount: number
}

export interface ChaosSimulationResponse {
  message?: string
  scenario?: string
  logsQueued?: number
}

export type TriggerCategory = 'CHAOS_SIMULATION' | 'TENANT_DEFECT'

export interface TriggerOption {
  id: string
  name: string
  category: TriggerCategory
  scenario?: 'PAYMENT_DB_DEADLOCK' | 'REDIS_OOM' | 'AUTH_NPE'
  serviceName: string
  description: string
  tenantScope: '11111111-1111-1111-1111-111111111111' | '22222222-2222-2222-2222-222222222222' | 'ALL'
  endpoint?: string
  defaultCount: number
  maxCount: number
  defectType?: string
}
