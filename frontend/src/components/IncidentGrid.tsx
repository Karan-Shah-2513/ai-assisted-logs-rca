import { IncidentTable } from './IncidentTable'
import type { Incident } from '../types'

export interface IncidentGridProps {
  incidents: Incident[]
  loading?: boolean
  error?: string | null
  onRetry?: () => void
  tenantName?: string
  selectedIncidentId?: string | null
  onSelect?: (incident: Incident) => void
}

/**
 * Minimal responsive incident table component.
 * Implemented as a lightweight, accessible semantic HTML table with RCA inspection.
 */
export function IncidentGrid({
  incidents,
  loading = false,
  error = null,
  onRetry,
  tenantName = 'Tenant',
  selectedIncidentId,
  onSelect,
}: IncidentGridProps) {
  return (
    <IncidentTable
      incidents={incidents}
      loading={loading}
      error={error}
      onRetry={onRetry}
      tenantName={tenantName}
      selectedIncidentId={selectedIncidentId}
      onSelectIncident={onSelect}
    />
  )
}

export { IncidentTable }
