import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Client, type IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { AlertTriangle, Check, Layers, RefreshCw, Search } from 'lucide-react'
import { fetchIncidents, fetchTelemetry } from './api'
import { getTenantApiKey, setTenantApiKey } from './auth'
import { tenants } from './data'
import { IncidentTable } from './components/IncidentTable'
import { TopBar } from './components/TopBar'
import { ChaosSimulator } from './components/ChaosSimulator'
import { RcaDrawer } from './components/RcaDrawer'
import type { Incident, IncidentStatus, Telemetry } from './types'

type ConnectionState = 'connecting' | 'live' | 'offline'
type IncidentFilter = 'ALL' | IncidentStatus

function isIncident(value: unknown): value is Incident {
  return Boolean(
    value &&
      typeof value === 'object' &&
      'id' in value &&
      'serviceName' in value &&
      'occurrenceCount' in value,
  )
}

function formatUpdatedAt() {
  return new Intl.DateTimeFormat('en', { hour: 'numeric', minute: '2-digit', second: '2-digit' }).format(
    new Date(),
  )
}

export default function App() {
  const [tenantId, setTenantId] = useState(tenants[0].id)
  const [apiKey, setApiKey] = useState(() => getTenantApiKey(tenants[0].id))

  // Real production/live data states (no fabricated seed data)
  const [incidents, setIncidents] = useState<Incident[]>([])
  const [telemetry, setTelemetry] = useState<Telemetry | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingTelemetry, setLoadingTelemetry] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [lastSynced, setLastSynced] = useState<string>(formatUpdatedAt)

  const [selectedIncident, setSelectedIncident] = useState<Incident | null>(null)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const [filter, setFilter] = useState<IncidentFilter>('ALL')
  const [query, setQuery] = useState('')
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'warning' | 'error' } | null>(
    null,
  )

  const activeTenant = useMemo(
    () => tenants.find((t) => t.id === tenantId) || tenants[0],
    [tenantId],
  )

  const notify = useCallback((message: string, type: 'success' | 'warning' | 'error' = 'success') => {
    setToast({ message, type })
    window.setTimeout(() => setToast((current) => (current?.message === message ? null : current)), 4500)
  }, [])

  const latestRequestIdRef = useRef(0)

  // Handle switching tenants - immediately clears prior tenant state
  const handleTenantChange = (newTenantId: string) => {
    // Invalidate in-flight REST request identity immediately
    latestRequestIdRef.current++

    // Immediately clear prior tenant's incidents and telemetry, plus open drawer
    setIncidents([])
    setTelemetry(null)
    setError(null)
    setSelectedIncident(null)
    setLoading(true)
    setLoadingTelemetry(true)

    setTenantId(newTenantId)
    const newKey = getTenantApiKey(newTenantId)
    setApiKey(newKey)
  }

  // Handle live resolution update
  const handleIncidentUpdated = (updated: Incident) => {
    setIncidents((current) => {
      const index = current.findIndex((i) => i.id === updated.id)
      if (index < 0) return [updated, ...current]
      const next = [...current]
      next[index] = updated
      return next
    })
    setSelectedIncident((current) => (current?.id === updated.id ? updated : current))
    void loadData(true)
  }

  // Handle user updating the API key in the session-scoped UI
  const handleApiKeyChange = (newKey: string) => {
    setApiKey(newKey)
    setTenantApiKey(tenantId, newKey)
    notify(`X-API-Key updated for ${activeTenant.name}`)
  }

  // Load incidents and telemetry via REST with X-Tenant-ID and X-API-Key
  const loadData = useCallback(
    async (isManualRefresh = false) => {
      const currentRequestId = ++latestRequestIdRef.current
      const requestedTenantId = tenantId
      const requestedTenantName = activeTenant.name

      if (isManualRefresh) setRefreshing(true)
      else {
        setLoading(true)
        setLoadingTelemetry(true)
      }
      setError(null)

      try {
        const [incidentResult, telemetryResult] = await Promise.allSettled([
          fetchIncidents(requestedTenantId, apiKey),
          fetchTelemetry(requestedTenantId, apiKey),
        ])

        // Guard async REST result with active/request identity
        if (currentRequestId !== latestRequestIdRef.current) {
          return
        }

        if (incidentResult.status === 'fulfilled') {
          setIncidents(incidentResult.value)
          setError(null)
        } else {
          const reason = incidentResult.reason
          const status = reason?.response?.status
          const statusText = reason?.response?.data?.message || reason?.message || 'Unknown network error'
          if (status === 401) {
            setError(`401 Unauthorized: Invalid or missing X-API-Key for ${requestedTenantName}.`)
          } else if (status === 403) {
            setError(`403 Forbidden: X-Tenant-ID does not match authenticated key credentials.`)
          } else {
            setError(`Failed to fetch incidents: ${statusText}`)
          }
          setIncidents([])
        }

        if (telemetryResult.status === 'fulfilled') {
          setTelemetry(telemetryResult.value)
        } else {
          // If telemetry fails, do not fabricate numbers
          setTelemetry(null)
        }

        setLastSynced(formatUpdatedAt())
        if (isManualRefresh && incidentResult.status === 'fulfilled') {
          notify(`Synchronized latest telemetry for ${requestedTenantName}`)
        }
      } catch (err: unknown) {
        if (currentRequestId !== latestRequestIdRef.current) {
          return
        }
        const message = err instanceof Error ? err.message : 'Error communicating with backend'
        setError(message)
        setIncidents([])
        setTelemetry(null)
      } finally {
        if (currentRequestId === latestRequestIdRef.current) {
          setLoading(false)
          setLoadingTelemetry(false)
          setRefreshing(false)
        }
      }
    },
    [tenantId, apiKey, activeTenant.name, notify],
  )

  // Trigger REST fetch when tenant or API key changes
  useEffect(() => {
    void loadData(false)
    return () => {
      // Invalidate on unmount or deps change to prevent stale responses
      latestRequestIdRef.current++
    }
  }, [loadData])

  // STOMP WebSocket connection: connects with X-Tenant-ID and X-API-Key
  useEffect(() => {
    let active = true
    const currentTenantId = tenantId

    if (!apiKey || !apiKey.trim()) {
      if (!active) return
      setConnection('offline')
      return
    }

    if (!active) return
    setConnection('connecting')

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws') as WebSocket,
      connectHeaders: {
        'X-Tenant-ID': currentTenantId,
        'X-API-Key': apiKey.trim(),
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        if (!active) return
        setConnection('live')

        // Subscribe to live incident events for the current tenant
        client.subscribe(`/topic/tenant/${currentTenantId}/incidents`, (message: IMessage) => {
          if (!active) return
          try {
            const event: unknown = JSON.parse(message.body)
            if (isIncident(event)) {
              if (!active) return
              setIncidents((current) => {
                if (!active) return current
                const index = current.findIndex(
                  (i) => i.id === event.id || (event.fingerprintHash && i.fingerprintHash === event.fingerprintHash),
                )
                if (index < 0) return [event, ...current]
                const next = [...current]
                next[index] = { ...next[index], ...event }
                return next
              })
              if (!active) return
              setLastSynced(formatUpdatedAt())
            }
          } catch (err) {
            console.error('Failed to parse incident event frame', err)
          }
        })

        // Subscribe to live telemetry counter events for the current tenant
        client.subscribe(`/topic/tenant/${currentTenantId}/telemetry`, (message: IMessage) => {
          if (!active) return
          try {
            const event = JSON.parse(message.body) as Telemetry
            if (typeof event.totalLogs === 'number') {
              if (!active) return
              setTelemetry(event)
              if (!active) return
              setLastSynced(formatUpdatedAt())
            }
          } catch (err) {
            console.error('Failed to parse telemetry event frame', err)
          }
        })
      },
      onWebSocketClose: () => {
        if (!active) return
        setConnection('offline')
      },
      onStompError: (frame) => {
        if (!active) return
        setConnection('offline')
        const errorMsg = frame.headers['message'] || 'STOMP authorization failed'
        console.warn('STOMP connection error:', errorMsg)
      },
      onWebSocketError: () => {
        if (!active) return
        setConnection('offline')
      },
    })

    client.activate()

    return () => {
      active = false
      void client.deactivate()
    }
  }, [tenantId, apiKey])

  // Filter and search incidents
  const visibleIncidents = useMemo(() => {
    return incidents.filter((incident) => {
      const matchesStatus = filter === 'ALL' || incident.status === filter
      const needle = query.trim().toLowerCase()
      const matchesSearch =
        !needle ||
        [
          incident.serviceName,
          incident.sanitizedStackTrace,
          incident.aiRcaJson?.rootCauseSummary,
        ].some((value) => value?.toLowerCase().includes(needle))
      return matchesStatus && matchesSearch
    })
  }, [filter, incidents, query])

  const filters: { label: string; value: IncidentFilter }[] = [
    { label: 'All signals', value: 'ALL' },
    { label: 'Open', value: 'OPEN' },
    { label: 'Investigating', value: 'INVESTIGATING' },
    { label: 'Resolved', value: 'RESOLVED' },
  ]

  return (
    <div className="app-shell">
      <TopBar
        tenants={tenants}
        tenantId={tenantId}
        onTenantChange={handleTenantChange}
        apiKey={apiKey}
        onApiKeyChange={handleApiKeyChange}
        telemetry={telemetry}
        loadingTelemetry={loadingTelemetry}
        connection={connection}
        onRefresh={() => void loadData(true)}
        refreshing={refreshing}
      />

      <main className="main-content">
        <ChaosSimulator
          tenantId={tenantId}
          tenantName={activeTenant.name}
          apiKey={apiKey}
          onTriggerSuccess={(msg) => {
            notify(msg, 'success')
            void loadData(true)
          }}
        />

        <div className="incidents-toolbar">
          <div className="toolbar-tabs" role="tablist" aria-label="Filter incidents by status">
            {filters.map(({ label, value }) => {
              const count =
                value === 'ALL'
                  ? incidents.length
                  : incidents.filter((i) => i.status === value).length
              return (
                <button
                  type="button"
                  role="tab"
                  aria-selected={filter === value}
                  className={filter === value ? 'filter-tab active' : 'filter-tab'}
                  onClick={() => setFilter(value)}
                  key={value}
                >
                  {label}
                  <span className="tab-count">{count}</span>
                </button>
              )
            })}
          </div>

          <div className="toolbar-actions">
            <label className="incident-search">
              <Search size={14} aria-hidden="true" />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search service or stack trace..."
                aria-label="Filter incidents"
              />
            </label>
            <button
              type="button"
              className="toolbar-button"
              onClick={() => void loadData(true)}
              title="Refresh incidents"
            >
              <RefreshCw size={13} className={refreshing ? 'spin' : ''} />
              <span>Refresh</span>
            </button>
          </div>
        </div>

        <section className="incident-panel" aria-label="Incident signals table">
          <div className="panel-heading">
            <div className="panel-title-group">
              <div className="panel-title-row">
                <h2>Tenant Incidents</h2>
                {connection === 'live' && (
                  <span className="live-indicator">
                    <i aria-hidden="true" />
                    STREAM LIVE
                  </span>
                )}
              </div>
              <p>Correlated exception signals scoped to {activeTenant.name}</p>
            </div>
            <div className="panel-meta">
              <Layers size={13} aria-hidden="true" />
              <span>{visibleIncidents.length} signals visible</span>
            </div>
          </div>

          <IncidentTable
            incidents={visibleIncidents}
            loading={loading}
            error={error}
            onRetry={() => void loadData(true)}
            tenantName={activeTenant.name}
            selectedIncidentId={selectedIncident?.id}
            onSelectIncident={setSelectedIncident}
          />

          <div className="table-footer">
            <span className="footer-topic-info">
              <i className={connection === 'live' ? 'footer-live-dot' : 'footer-offline-dot'} />
              Topic: <code>/topic/tenant/{tenantId}/incidents</code>
            </span>
            <span>Last synced {lastSynced}</span>
          </div>
        </section>

        <footer className="content-footer">
          <div className={`stream-status ${connection}`}>
            <span className="stream-icon">
              {connection === 'live' ? '●' : connection === 'connecting' ? '◌' : '○'}
            </span>
            <span>
              {connection === 'live'
                ? `STOMP connected with ${apiKey ? 'authenticated key' : 'anonymous'} credentials`
                : connection === 'connecting'
                ? 'Connecting to STOMP WebSocket broker…'
                : 'STOMP disconnected (configure a valid X-API-Key to stream live)'}
            </span>
          </div>
          <div className="footer-right">
            <span>REST: <code>/api/v1/incidents</code></span>
            <span>Tenant: <code>{tenantId.slice(0, 8)}…</code></span>
          </div>
        </footer>
      </main>

      {selectedIncident && (
        <RcaDrawer
          incident={selectedIncident}
          tenantId={tenantId}
          tenantName={activeTenant.name}
          apiKey={apiKey}
          onClose={() => setSelectedIncident(null)}
          onIncidentUpdated={handleIncidentUpdated}
          onNotify={notify}
        />
      )}

      {toast && (
        <div className={`toast-message toast-${toast.type}`} role="status">
          <span>
            {toast.type === 'error' ? (
              <AlertTriangle size={14} />
            ) : (
              <Check size={14} />
            )}
          </span>
          {toast.message}
        </div>
      )}
    </div>
  )
}
