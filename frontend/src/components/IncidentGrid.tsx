import { useMemo } from 'react'
import { AgGridReact } from 'ag-grid-react'
import { ClientSideRowModelModule, ModuleRegistry, type ColDef, type ICellRendererParams } from 'ag-grid-community'
import { ArrowUpRight, CircleDot } from 'lucide-react'
import type { Incident, IncidentStatus, Severity } from '../types'

ModuleRegistry.registerModules([ClientSideRowModelModule])

interface IncidentGridProps {
  incidents: Incident[]
  onSelect: (incident: Incident) => void
}

function SeverityCell({ value }: ICellRendererParams<Incident, Severity>) {
  return <span className={`severity-pill severity-${value?.toLowerCase()}`}><i />{value}</span>
}

function IncidentCell({ data }: ICellRendererParams<Incident>) {
  if (!data) return null
  const summary = data.aiRcaJson?.rootCauseSummary || data.sanitizedStackTrace
  return (
    <div className="incident-cell">
      <span className="incident-type">{data.sanitizedStackTrace?.split(':')[0] || 'Incident detected'}</span>
      <span className="incident-summary">{summary}</span>
    </div>
  )
}

function StatusCell({ value }: ICellRendererParams<Incident, IncidentStatus>) {
  return <span className={`status-pill status-${value?.toLowerCase()}`}><i />{value?.toLowerCase()}</span>
}

function OccurrenceCell({ value }: ICellRendererParams<Incident, number>) {
  return <span className="occurrence-value"><span className="occurrence-dot" />{Number(value || 0).toLocaleString()}</span>
}

function ConfidenceCell({ data }: ICellRendererParams<Incident>) {
  const score = data?.aiRcaJson?.confidenceScore ?? 0
  return (
    <span className="confidence-cell">
      <span className="confidence-track"><i style={{ width: `${score}%` }} /></span>
      <span>{score}%</span>
    </span>
  )
}

const formatSeen = (value?: string) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const seconds = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000))
  if (seconds < 60) return `${seconds}s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h ago`
  return date.toLocaleDateString()
}

export function IncidentGrid({ incidents, onSelect }: IncidentGridProps) {
  const columnDefs = useMemo<ColDef<Incident>[]>(() => [
    { headerName: 'Severity', field: 'aiRcaJson.severity', width: 112, cellRenderer: SeverityCell },
    { headerName: 'Service', field: 'serviceName', minWidth: 150, flex: 0.85, cellClass: 'service-name-cell' },
    { headerName: 'Exception / summary', field: 'sanitizedStackTrace', minWidth: 310, flex: 2, cellRenderer: IncidentCell },
    {
      headerName: 'Occurrences',
      field: 'occurrenceCount',
      width: 132,
      cellRenderer: OccurrenceCell,
      enableCellChangeFlash: true,
    },
    { headerName: 'AI confidence', field: 'aiRcaJson.confidenceScore', width: 142, cellRenderer: ConfidenceCell },
    { headerName: 'Status', field: 'status', width: 142, cellRenderer: StatusCell },
    { headerName: 'Last seen', field: 'lastSeen', width: 124, valueFormatter: (params) => formatSeen(params.value) },
    {
      headerName: '',
      width: 46,
      sortable: false,
      filter: false,
      cellRenderer: () => <ArrowUpRight size={15} className="row-open-icon" />,
    },
  ], [])

  return (
    <section className="incident-panel">
      <div className="panel-heading">
        <div>
          <div className="panel-title-row"><h2>Live incidents</h2><span className="live-indicator"><i />LIVE</span></div>
          <p>Correlated exceptions across your production services</p>
        </div>
        <div className="panel-meta"><CircleDot size={13} /><span>{incidents.length} active signals</span></div>
      </div>
      {incidents.length > 0 ? (
        <div className="ag-theme-quartz-dark incident-grid">
          <AgGridReact<Incident>
            rowData={incidents}
            columnDefs={columnDefs}
            defaultColDef={{ sortable: true, resizable: true, suppressHeaderMenuButton: true }}
            getRowId={(params) => params.data.id}
            rowSelection="single"
            suppressCellFocus
            animateRows
            onRowClicked={(event) => event.data && onSelect(event.data)}
            overlayNoRowsTemplate="<span>No incidents for this tenant</span>"
          />
        </div>
      ) : (
        <div className="empty-state"><span className="empty-icon"><CircleDot size={20} /></span><strong>All quiet for now</strong><span>New incidents will appear here as they are detected.</span></div>
      )}
      <div className="table-footer"><span><i className="footer-live-dot" />Streaming updates enabled</span><span>Updated just now</span></div>
    </section>
  )
}
