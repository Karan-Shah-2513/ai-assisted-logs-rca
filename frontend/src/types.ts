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
  aiRcaJson: AiRcaReport
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
