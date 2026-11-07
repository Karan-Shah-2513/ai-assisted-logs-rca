import { Component, type ErrorInfo, type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { Client, type IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { ArrowDownUp, Check, CircleHelp, Filter, Radio, Search, Wifi, WifiOff } from 'lucide-react'
import { fetchIncidents, fetchTelemetry, resolveIncident, simulateOutage } from './api'
import { seedIncidents, seedTelemetry, tenants } from './data'
import { IncidentGrid } from './components/IncidentGrid'
import { RcaDrawer } from './components/RcaDrawer'
import { TopBar } from './components/TopBar'
import type { ChaosScenario } from './components/ChaosSimulator'
import type { Incident, IncidentStatus, Telemetry } from './types'

type ConnectionState = 'connecting' | 'live' | 'offline'
type IncidentFilter = 'ALL' | IncidentStatus

interface IncidentGridBoundaryProps {
  children: ReactNode
}

interface IncidentGridBoundaryState {
  hasError: boolean
}

class IncidentGridBoundary extends Component<IncidentGridBoundaryProps, IncidentGridBoundaryState> {
  state: IncidentGridBoundaryState = { hasError: false }

  static getDerivedStateFromError(): IncidentGridBoundaryState {
    return { hasError: true }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('Incident grid failed to render', error, errorInfo)
  }

  render() {
    if (this.state.hasError) {
      return (
        <section className="grid-error-fallback" role="alert">
          <strong>Incident list is temporarily unavailable</strong>
          <span>The incident grid encountered a rendering error. Other dashboard data is still available.</span>
        </section>
      )
    }

    return this.props.children
  }
}

function isIncident(value: unknown): value is Incident {
  return Boolean(value && typeof value === 'object' && 'id' in value && 'serviceName' in value && 'occurrenceCount' in value)
}

function formatUpdatedAt() {
  return new Intl.DateTimeFormat('en', { hour: 'numeric', minute: '2-digit' }).format(new Date())
}

export default function App() {
  const [tenantId, setTenantId] = useState(tenants[0].id)
  const [incidents, setIncidents] = useState<Incident[]>(() => seedIncidents)
  const [telemetry, setTelemetry] = useState<Telemetry>(() => seedTelemetry)
  const [selectedIncident, setSelectedIncident] = useState<Incident | null>(null)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const [loadingSimulation, setLoadingSimulation] = useState(false)
  const [resolvingId, setResolvingId] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [usingDemoData, setUsingDemoData] = useState(true)
  const [filter, setFilter] = useState<IncidentFilter>('ALL')
  const [query, setQuery] = useState('')

  const notify = useCallback((message: string) => {
    setToast(message)
    window.setTimeout(() => setToast((current) => current === message ? null : current), 4500)
  }, [])

  const updateIncident = useCallback((updated: Incident) => {
    setIncidents((current) => {
      const index = current.findIndex((incident) => incident.id === updated.id || incident.fingerprintHash === updated.fingerprintHash)
      if (index < 0) return [updated, ...current]
      const next = [...current]
      next[index] = { ...next[index], ...updated }
      return next
    })
    setSelectedIncident((current) => current && (current.id === updated.id || current.fingerprintHash === updated.fingerprintHash)
      ? { ...current, ...updated }
      : current)
  }, [])

  useEffect(() => {
    let active = true
    setUsingDemoData(true)
    setIncidents(seedIncidents.map((incident) => ({ ...incident, tenantId })))
    setTelemetry({ ...seedTelemetry, tenantId })
    setSelectedIncident(null)

    Promise.allSettled([fetchIncidents(tenantId), fetchTelemetry(tenantId)]).then(([incidentResult, telemetryResult]) => {
      if (!active) return
      if (incidentResult.status === 'fulfilled') {
        setIncidents(incidentResult.value)
      }
      if (telemetryResult.status === 'fulfilled') {
        setTelemetry(telemetryResult.value)
      }
      setUsingDemoData(incidentResult.status !== 'fulfilled' || telemetryResult.status !== 'fulfilled')
    })

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws') as WebSocket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        if (!active) return
        setConnection('live')
        client.subscribe(`/topic/tenant/${tenantId}/incidents`, (message: IMessage) => {
          try {
            const event: unknown = JSON.parse(message.body)
            if (isIncident(event)) updateIncident(event)
          } catch (error) {
            console.error('Unable to parse incident stream event', error)
          }
        })
        client.subscribe(`/topic/tenant/${tenantId}/telemetry`, (message: IMessage) => {
          try {
            const event = JSON.parse(message.body) as Telemetry
            if (typeof event.totalLogs === 'number' && event.tenantId === tenantId) setTelemetry(event)
          } catch (error) {
            console.error('Unable to parse telemetry stream event', error)
          }
        })
      },
      onWebSocketClose: () => active && setConnection('offline'),
      onStompError: () => active && setConnection('offline'),
      onWebSocketError: () => active && setConnection('offline'),
    })
    client.activate()

    return () => {
      active = false
      void client.deactivate()
    }
  }, [tenantId, updateIncident])

  const visibleIncidents = useMemo(() => incidents.filter((incident) => {
    const matchesStatus = filter === 'ALL' || incident.status === filter
    const needle = query.trim().toLowerCase()
    const matchesSearch = !needle || [incident.serviceName, incident.sanitizedStackTrace, incident.aiRcaJson?.rootCauseSummary]
      .some((value) => value?.toLowerCase().includes(needle))
    return matchesStatus && matchesSearch
  }), [filter, incidents, query])

  const handleSimulation = async (scenario: ChaosScenario, logCount: number) => {
    setLoadingSimulation(true)
    try {
      const result = await simulateOutage(tenantId, scenario, logCount)
      notify(`${result.message || 'Chaos simulation triggered'} · ${result.logsQueued ?? logCount} logs queued`)
      const [incidentResult, telemetryResult] = await Promise.allSettled([fetchIncidents(tenantId), fetchTelemetry(tenantId)])
      if (incidentResult.status === 'fulfilled') setIncidents(incidentResult.value)
      if (telemetryResult.status === 'fulfilled') setTelemetry(telemetryResult.value)
      setUsingDemoData(incidentResult.status !== 'fulfilled' || telemetryResult.status !== 'fulfilled')
    } catch {
      notify('Could not trigger the outage. Check that the backend is running and try again.')
    } finally {
      setLoadingSimulation(false)
    }
  }

  const handleResolve = async (incident: Incident) => {
    setResolvingId(incident.id)
    try {
      const updated = await resolveIncident(tenantId, incident.id)
      updateIncident(updated)
      notify('Incident resolved and added to the knowledge base.')
    } catch {
      notify('Unable to resolve this incident. Please retry when the API is available.')
    } finally {
      setResolvingId(null)
    }
  }

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
        onTenantChange={setTenantId}
        telemetry={telemetry}
        busy={loadingSimulation}
        onSimulate={handleSimulation}
      />
      <main className="main-content">
        <div className="incidents-toolbar">
          <div className="toolbar-tabs" role="tablist" aria-label="Filter incidents">
            {filters.map(({ label, value }) => (
              <button
                type="button"
                role="tab"
                aria-selected={filter === value}
                className={filter === value ? 'filter-tab active' : 'filter-tab'}
                onClick={() => setFilter(value)}
                key={value}
              >{label}{value === 'ALL' && <span className="tab-count">{incidents.length}</span>}</button>
            ))}
          </div>
          <div className="toolbar-actions">
            <label className="incident-search">
              <Search size={14} />
              <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search incidents..." />
              <kbd>/</kbd>
            </label>
            <button type="button" className="toolbar-button"><Filter size={14} /> Filters</button>
            <button type="button" className="toolbar-button sort-button"><ArrowDownUp size={14} /> Newest</button>
          </div>
        </div>
        <IncidentGridBoundary>
          <IncidentGrid incidents={visibleIncidents} onSelect={setSelectedIncident} />
        </IncidentGridBoundary>
        <footer className="content-footer">
          <div className={`stream-status ${connection}`}><span className="stream-icon">{connection === 'live' ? <Wifi size={13} /> : <WifiOff size={13} />}</span>
            {connection === 'live' ? 'Connected to live event stream' : connection === 'connecting' ? 'Connecting to live event stream…' : 'Reconnecting to event stream'}
          </div>
          <div className="footer-right">
            {usingDemoData && <span className="demo-mode"><Radio size={12} /> Demo data · API unavailable</span>}
            <span>Last synced {formatUpdatedAt()}</span>
            <button type="button" className="footer-help"><CircleHelp size={13} /> Status page</button>
          </div>
        </footer>
      </main>
      {selectedIncident && (
        <RcaDrawer
          incident={selectedIncident}
          resolving={resolvingId === selectedIncident.id}
          onClose={() => setSelectedIncident(null)}
          onResolve={handleResolve}
          onError={notify}
        />
      )}
      {toast && <div className="toast-message" role="status"><span><Check size={14} /></span>{toast}</div>}
    </div>
  )
}
