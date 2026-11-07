import { Activity, CircleDollarSign, Layers3, Workflow } from 'lucide-react'
import type { Telemetry } from '../types'

interface KpiCardsProps {
  telemetry: Telemetry
}

const number = new Intl.NumberFormat('en-US')

export function KpiCards({ telemetry }: KpiCardsProps) {
  const cards = [
    {
      label: 'Total logs processed',
      value: number.format(telemetry.totalLogs),
      icon: Activity,
      note: 'Across active services',
      tone: 'lime',
    },
    {
      label: 'Deduplicated ratio',
      value: `${telemetry.deduplicationRatio.toFixed(1)}%`,
      icon: Layers3,
      note: `${number.format(telemetry.deduplicatedLogs)} noise events filtered`,
      tone: 'blue',
    },
    {
      label: 'Unique incidents',
      value: number.format(telemetry.uniqueIncidents),
      icon: Workflow,
      note: 'Grouped by fingerprint',
      tone: 'violet',
    },
    {
      label: 'Estimated LLM cost saved',
      value: `$${telemetry.estimatedCostSavedUsd.toFixed(2)}`,
      icon: CircleDollarSign,
      note: 'By deduplicating repeated logs',
      tone: 'amber',
    },
  ]

  return (
    <section className="kpi-grid" aria-label="Telemetry overview">
      {cards.map(({ label, value, icon: Icon, note, tone }) => (
        <article className="kpi-card" key={label}>
          <div className={`kpi-icon ${tone}`}><Icon size={16} strokeWidth={1.8} /></div>
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
