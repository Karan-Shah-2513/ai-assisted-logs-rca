import { Activity, CircleDollarSign, Layers3, ShieldCheck, Workflow } from 'lucide-react'
import type { Telemetry } from '../types'

interface KpiCardsProps {
  telemetry: Telemetry | null
  loading?: boolean
}

const number = new Intl.NumberFormat('en-US')

export function KpiCards({ telemetry, loading }: KpiCardsProps) {
  const cards = [
    {
      label: 'Total logs processed',
      value: telemetry ? number.format(telemetry.totalLogs) : loading ? '…' : '0',
      icon: Activity,
      note: 'Across active tenant services',
      tone: 'lime',
    },
    {
      label: 'Deduplicated ratio',
      value: telemetry ? `${telemetry.deduplicationRatio.toFixed(1)}%` : loading ? '…' : '0.0%',
      icon: Layers3,
      note: telemetry
        ? `${number.format(telemetry.deduplicatedLogs)} noise events filtered`
        : 'Noise reduction active',
      tone: 'blue',
    },
    {
      label: 'Unique incidents',
      value: telemetry ? number.format(telemetry.uniqueIncidents) : loading ? '…' : '0',
      icon: Workflow,
      note: 'Grouped by fingerprint hash',
      tone: 'violet',
    },
    {
      label: 'Estimated LLM cost saved',
      value: telemetry ? `$${telemetry.estimatedCostSavedUsd.toFixed(2)}` : loading ? '…' : '$0.00',
      icon: CircleDollarSign,
      note: 'Through log deduplication',
      tone: 'amber',
    },
    {
      label: 'Decision-Gated / Auto-Triaged',
      value: telemetry ? number.format(telemetry.totalDecisions ?? 0) : loading ? '…' : '0',
      icon: ShieldCheck,
      note: telemetry
        ? `${Math.round(telemetry.averageDecisionLatencyMs ?? 0)}ms decision latency`
        : 'System 1 gate active',
      tone: 'teal',
    },
  ]

  return (
    <section className="kpi-grid" aria-label="Tenant telemetry overview">
      {cards.map(({ label, value, icon: Icon, note, tone }) => (
        <article className={`kpi-card ${loading ? 'is-loading' : ''}`} key={label}>
          <div className={`kpi-icon ${tone}`} aria-hidden="true">
            <Icon size={16} strokeWidth={1.8} />
          </div>
          <div className="kpi-copy">
            <p className="kpi-label">{label}</p>
            <p className="kpi-value">{value}</p>
            <p className="kpi-note">{note}</p>
          </div>
        </article>
      ))}
    </section>
  )
}
