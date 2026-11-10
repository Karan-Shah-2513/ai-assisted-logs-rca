export type IncidentStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED'
export type Severity = 'P0' | 'P1' | 'P2' | 'P3'

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
}

export interface Telemetry {
  tenantId: string
  totalLogs: number
  deduplicatedLogs: number
  uniqueIncidents: number
  deduplicationRatio: number
  estimatedCostSavedUsd: number
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
