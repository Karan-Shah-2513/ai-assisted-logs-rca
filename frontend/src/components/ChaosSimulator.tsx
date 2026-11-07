import { useState } from 'react'
import { ChevronDown, Radio, Zap } from 'lucide-react'

export const scenarios = ['PAYMENT_DB_DEADLOCK', 'REDIS_OOM', 'AUTH_NPE'] as const
export type ChaosScenario = typeof scenarios[number]

interface ChaosSimulatorProps {
  busy: boolean
  onTrigger: (scenario: ChaosScenario, logCount: number) => void
}

export function ChaosSimulator({ busy, onTrigger }: ChaosSimulatorProps) {
  const [scenario, setScenario] = useState<ChaosScenario>(scenarios[0])
  const [logCount, setLogCount] = useState(500)

  return (
    <section className="chaos-card" aria-label="Chaos simulator">
      <div className="chaos-heading">
        <span className="chaos-icon"><Radio size={16} /></span>
        <div>
          <h2>Chaos simulator</h2>
          <p>Inject a controlled failure</p>
        </div>
        <span className="lab-badge">LAB</span>
      </div>
      <div className="chaos-controls">
        <label className="control-field scenario-field">
          <span>Scenario</span>
          <span className="select-wrap">
            <select value={scenario} onChange={(event) => setScenario(event.target.value as ChaosScenario)}>
              {scenarios.map((option) => <option key={option}>{option}</option>)}
            </select>
            <ChevronDown size={13} aria-hidden="true" />
          </span>
        </label>
        <label className="control-field count-field">
          <span>Log count</span>
          <input
            type="number"
            min={1}
            max={10_000}
            step={100}
            value={logCount}
            onChange={(event) => setLogCount(Math.min(10_000, Math.max(1, Number(event.target.value) || 1)))}
          />
        </label>
        <button
          className="blast-button"
          type="button"
          disabled={busy}
          onClick={() => onTrigger(scenario, logCount)}
        >
          <Zap size={15} fill="currentColor" />
          {busy ? 'Launching…' : 'Blast outage'}
        </button>
      </div>
    </section>
  )
}
