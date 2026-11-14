import { AlertCircle, CheckCircle2, Clock, Hash, Layers, RefreshCw, Sparkles } from 'lucide-react'
import type { Incident, IncidentStatus, Severity } from '../types'

interface IncidentTableProps {
  incidents: Incident[]
  loading: boolean
  error: string | null
  onRetry?: () => void
  tenantName: string
  selectedIncidentId?: string | null
  onSelectIncident?: (incident: Incident) => void
}

function SeverityBadge({ severity }: { severity?: Severity | null }) {
  if (!severity) return null
  return (
    <span className={`severity-pill severity-${severity.toLowerCase()}`}>
      <i aria-hidden="true" />
      {severity}
    </span>
  )
}

function StatusBadge({ status }: { status: IncidentStatus }) {
  return (
    <span className={`status-pill status-${status.toLowerCase()}`}>
      <i aria-hidden="true" />
      {status.toLowerCase()}
    </span>
  )
}

function formatRelativeTime(dateString?: string): string {
  if (!dateString) return '—'
  const date = new Date(dateString)
  if (Number.isNaN(date.getTime())) return '—'

  const diffMs = Date.now() - date.getTime()
  if (diffMs < 0) return 'just now'
  const seconds = Math.floor(diffMs / 1000)
  if (seconds < 45) return 'just now'
  if (seconds < 90) return '1m ago'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}d ago`
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function formatFullTime(dateString?: string): string {
  if (!dateString) return ''
  const date = new Date(dateString)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString()
}

export function IncidentTable({
  incidents,
  loading,
  error,
  onRetry,
  tenantName,
  selectedIncidentId,
  onSelectIncident,
}: IncidentTableProps) {
  // 1. Loading State
  if (loading && incidents.length === 0) {
    return (
      <div className="incident-state-container" role="status" aria-label="Loading incidents">
        <div className="incident-loading-indicator">
          <div className="loading-spinner" aria-hidden="true" />
          <p className="loading-text">Loading incidents for {tenantName}…</p>
        </div>
      </div>
    )
  }

  // 2. Error State
  if (error && incidents.length === 0) {
    return (
      <div className="incident-state-container error-state" role="alert" aria-label="Error loading incidents">
        <div className="incident-error-box">
          <span className="error-icon" aria-hidden="true">
            <AlertCircle size={28} />
          </span>
          <div className="error-content">
            <h3>Unable to load incidents</h3>
            <p className="error-description">{error}</p>
            {error.toLowerCase().includes('401') || error.toLowerCase().includes('api key') ? (
              <p className="error-hint">
                Hint: Ensure a valid <code>X-API-Key</code> is configured in the header above for {tenantName}.
              </p>
            ) : null}
          </div>
          {onRetry && (
            <button type="button" onClick={onRetry} className="incident-retry-btn">
              <RefreshCw size={13} />
              <span>Retry request</span>
            </button>
          )}
        </div>
      </div>
    )
  }

  // 3. Empty State
  if (incidents.length === 0) {
    return (
      <div className="incident-state-container empty-state" role="status" aria-label="No incidents">
        <span className="empty-icon" aria-hidden="true">
          <CheckCircle2 size={26} />
        </span>
        <strong>All quiet for {tenantName}</strong>
        <p>No active or recorded incidents for this tenant. Telemetry stream is active.</p>
        {onRetry && (
          <button type="button" onClick={onRetry} className="quiet-refresh-btn">
            <RefreshCw size={12} />
            <span>Check again</span>
          </button>
        )}
      </div>
    )
  }

  // 4. Populated Table
  return (
    <div className="incident-table-wrapper">
      <table className="incident-table" aria-label={`Active incidents for ${tenantName}`}>
        <thead>
          <tr>
            <th scope="col" style={{ width: '150px' }}>
              <span className="th-content"><Layers size={11} /> Service</span>
            </th>
            <th scope="col" style={{ width: '120px' }}>Status</th>
            <th scope="col" style={{ width: '110px' }}>
              <span className="th-content"><Hash size={11} /> Occurrences</span>
            </th>
            <th scope="col" style={{ width: '110px' }}>
              <span className="th-content"><Clock size={11} /> Last Seen</span>
            </th>
            <th scope="col">Error / Summary</th>
            <th scope="col" style={{ width: '120px', textAlign: 'right' }}>Actions</th>
          </tr>
        </thead>
        <tbody>
          {incidents.map((incident) => {
            const summary =
              incident.aiRcaJson?.rootCauseSummary ||
              incident.sanitizedStackTrace?.split('\n')[0] ||
              'Incident detected'
            const fullTime = formatFullTime(incident.lastSeen)
            const relativeTime = formatRelativeTime(incident.lastSeen)
            const isSelected = selectedIncidentId === incident.id

            return (
              <tr
                key={incident.id}
                className={`incident-row ${isSelected ? 'is-selected' : ''}`}
                onClick={() => onSelectIncident?.(incident)}
                tabIndex={0}
                role="button"
                aria-pressed={isSelected}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onSelectIncident?.(incident)
                  }
                }}
              >
                <td className="service-cell">
                  <div className="service-name-wrap">
                    <span className="service-tag">{incident.serviceName}</span>
                    <SeverityBadge severity={incident.aiRcaJson?.severity} />
                  </div>
                </td>
                <td className="status-cell">
                  <StatusBadge status={incident.status} />
                </td>
                <td className="occurrence-cell">
                  <span className="occurrence-badge">
                    <span className="occurrence-dot" aria-hidden="true" />
                    {Number(incident.occurrenceCount || 0).toLocaleString()}
                  </span>
                </td>
                <td className="time-cell" title={fullTime}>
                  <time dateTime={incident.lastSeen}>{relativeTime}</time>
                </td>
                <td className="summary-cell">
                  <div className="summary-content">
                    <span className="summary-headline">{summary}</span>
                    {incident.sanitizedStackTrace && (
                      <span className="summary-trace" title={incident.sanitizedStackTrace}>
                        {incident.sanitizedStackTrace.split('\n')[0]}
                      </span>
                    )}
                  </div>
                </td>
                <td className="action-cell" style={{ textAlign: 'right' }}>
                  <button
                    type="button"
                    className="inspect-rca-btn"
                    onClick={(e) => {
                      e.stopPropagation()
                      onSelectIncident?.(incident)
                    }}
                    aria-label={`Inspect root cause analysis for ${incident.serviceName}`}
                  >
                    <Sparkles size={11} aria-hidden="true" />
                    <span>Inspect RCA</span>
                  </button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
