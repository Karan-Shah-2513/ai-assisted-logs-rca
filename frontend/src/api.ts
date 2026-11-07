import axios from 'axios'
import type { Incident, Telemetry } from './types'

const client = axios.create({ baseURL: '/api/v1', timeout: 8_000 })

const tenantConfig = (tenantId: string) => ({
  headers: { 'X-Tenant-ID': tenantId },
})

export async function fetchIncidents(tenantId: string): Promise<Incident[]> {
  const { data } = await client.get<Incident[]>('/incidents', tenantConfig(tenantId))
  return data
}

export async function fetchTelemetry(tenantId: string): Promise<Telemetry> {
  const { data } = await client.get<Telemetry>('/telemetry', tenantConfig(tenantId))
  return data
}

export async function simulateOutage(
  tenantId: string,
  scenario: string,
  logCount: number,
): Promise<{ message?: string; logsQueued?: number }> {
  const { data } = await client.post('/simulate-outage', {
    tenantId,
    scenario,
    logCount,
  })
  return data
}

export async function resolveIncident(tenantId: string, incidentId: string): Promise<Incident> {
  const { data } = await client.post<Incident>(
    `/incidents/${encodeURIComponent(incidentId)}/resolve`,
    {},
    tenantConfig(tenantId),
  )
  return data
}
