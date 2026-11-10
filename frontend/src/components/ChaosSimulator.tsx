import { useState, useId, useMemo } from 'react'
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  Flame,
  Radio,
  RefreshCw,
  Zap,
} from 'lucide-react'
import { simulateOutage, triggerTenantDefect } from '../api'
import {
  CHAOS_SCENARIOS,
  ALL_TRIGGER_OPTIONS,
  type ChaosScenario,
} from '../triggers'

export { CHAOS_SCENARIOS, ALL_TRIGGER_OPTIONS, type ChaosScenario }

interface ChaosSimulatorProps {
  tenantId: string
  tenantName: string
  apiKey: string
  onTriggerSuccess?: (message: string) => void
}

export function ChaosSimulator({
  tenantId,
  tenantName,
  apiKey,
  onTriggerSuccess,
}: ChaosSimulatorProps) {
  // Filter available triggers for the active tenant
  const tenantTriggers = useMemo(() => {
    return ALL_TRIGGER_OPTIONS.filter(
      (opt) => opt.tenantScope === 'ALL' || opt.tenantScope === tenantId,
    )
  }, [tenantId])

  const [selectedTriggerId, setSelectedTriggerId] = useState<string>(() => {
    return tenantTriggers[0]?.id || 'chaos-payment-db-deadlock'
  })

  // Synchronize selection when tenant changes
  const activeTrigger = useMemo(() => {
    return tenantTriggers.find((t) => t.id === selectedTriggerId) || tenantTriggers[0]
  }, [tenantTriggers, selectedTriggerId])

  const [logCount, setLogCount] = useState<number>(activeTrigger?.defaultCount || 500)
  const [confirmedCaution, setConfirmedCaution] = useState(true)
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState<{
    type: 'success' | 'error'
    message: string
  } | null>(null)

  const scenarioSelectId = useId()
  const burstCountId = useId()
  const cautionCheckboxId = useId()

  const maxAllowedCount = activeTrigger?.maxCount || 2000
  const isDirectDefect = activeTrigger?.category === 'TENANT_DEFECT'

  // Update default burst count when switching between chaos vs direct defect
  const handleTriggerChange = (newTriggerId: string) => {
    setSelectedTriggerId(newTriggerId)
    const nextTrigger = tenantTriggers.find((t) => t.id === newTriggerId)
    if (nextTrigger) {
      setLogCount(nextTrigger.defaultCount)
    }
    setFeedback(null)
  }

  const handleBurstCountChange = (valueStr: string) => {
    const parsed = parseInt(valueStr, 10)
    if (Number.isNaN(parsed)) {
      setLogCount(1)
      return
    }
    const clamped = Math.min(maxAllowedCount, Math.max(1, parsed))
    setLogCount(clamped)
  }

  const handleBlast = async () => {
    if (!activeTrigger) return
    if (!confirmedCaution) {
      setFeedback({
        type: 'error',
        message: 'Please confirm the intentional demo fault notice before blasting.',
      })
      return
    }

    setBusy(true)
    setFeedback(null)

    try {
      if (activeTrigger.category === 'CHAOS_SIMULATION' && activeTrigger.scenario) {
        const response = await simulateOutage(
          tenantId,
          activeTrigger.scenario,
          logCount,
          apiKey,
        )
        const msg =
          response.message ||
          `Triggered ${logCount} simulated log events for ${activeTrigger.scenario} on ${tenantName}.`
        setFeedback({ type: 'success', message: msg })
        onTriggerSuccess?.(msg)
      } else {
        const result = await triggerTenantDefect(
          activeTrigger.id,
          logCount,
          tenantId,
          apiKey,
        )
        if (result.success) {
          setFeedback({ type: 'success', message: result.message })
          onTriggerSuccess?.(result.message)
        } else {
          setFeedback({ type: 'error', message: result.message })
        }
      }
    } catch (err: unknown) {
      const axiosErr = err as {
        response?: { status: number; data?: { message?: string } }
        message?: string
      }
      const status = axiosErr.response?.status
      const detail = axiosErr.response?.data?.message || axiosErr.message || 'Unknown network error'

      if (status === 401) {
        setFeedback({
          type: 'error',
          message: `401 Unauthorized: Invalid or missing X-API-Key for ${tenantName}. Configure the key in the header bar.`,
        })
      } else if (status === 403) {
        setFeedback({
          type: 'error',
          message: `403 Forbidden: X-Tenant-ID does not match authenticated key credentials.`,
        })
      } else {
        setFeedback({
          type: 'error',
          message: `Fault injection failed: ${detail}`,
        })
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section
      className="chaos-card"
      aria-label="Bug trigger and chaos burst controls"
      role="region"
    >
      <div className="chaos-header-row">
        <div className="chaos-heading">
          <span className="chaos-icon" aria-hidden="true">
            <Radio size={16} />
          </span>
          <div>
            <div className="chaos-title-line">
              <h2>Fault Injection & Burst Controls</h2>
              <span className="tenant-scope-pill">
                Scoped: <strong>{tenantName}</strong>
              </span>
            </div>
            <p>Inject controlled outage bursts or trigger live defect endpoints</p>
          </div>
        </div>

        <div className="chaos-caution-box" role="note">
          <AlertTriangle size={14} className="caution-icon" aria-hidden="true" />
          <div className="caution-copy">
            <strong>Caution: Intentional Failure Injection</strong>
            <p>
              These triggers intentionally exercise broken demo endpoints to inject unhandled
              exceptions into tenant microservices. Logs are shipped to Airca for real-time
              deduplication, anomaly grouping, and automated RCA.
            </p>
          </div>
        </div>
      </div>

      <div className="chaos-controls-grid">
        <div className="control-group trigger-select-group">
          <label htmlFor={scenarioSelectId} className="control-label">
            Trigger / Scenario
          </label>
          <div className="select-wrap">
            <select
              id={scenarioSelectId}
              value={activeTrigger.id}
              onChange={(e) => handleTriggerChange(e.target.value)}
              disabled={busy}
              className="chaos-select"
            >
              <optgroup label="Central Chaos Simulator Outages">
                {tenantTriggers
                  .filter((t) => t.category === 'CHAOS_SIMULATION')
                  .map((opt) => (
                    <option key={opt.id} value={opt.id}>
                      {opt.name} ({opt.serviceName})
                    </option>
                  ))}
              </optgroup>

              <optgroup label={`${tenantName} Service Defect Endpoints`}>
                {tenantTriggers
                  .filter((t) => t.category === 'TENANT_DEFECT')
                  .map((opt) => (
                    <option key={opt.id} value={opt.id}>
                      {opt.name}
                    </option>
                  ))}
              </optgroup>
            </select>
            <ChevronDown size={13} className="select-chevron" aria-hidden="true" />
          </div>
          <span className="control-caption">{activeTrigger.description}</span>
        </div>

        <div className="control-group count-select-group">
          <div className="count-label-row">
            <label htmlFor={burstCountId} className="control-label">
              {isDirectDefect ? 'Burst Calls' : 'Log Burst Count'}
            </label>
            <span className="count-limit">Max: {maxAllowedCount.toLocaleString()}</span>
          </div>

          <div className="count-input-wrap">
            <input
              id={burstCountId}
              type="number"
              min={1}
              max={maxAllowedCount}
              step={isDirectDefect ? 1 : 50}
              value={logCount}
              onChange={(e) => handleBurstCountChange(e.target.value)}
              disabled={busy}
              className="count-input"
              aria-label="Burst count"
            />
            <div className="preset-buttons" role="group" aria-label="Burst presets">
              {isDirectDefect ? (
                <>
                  {[1, 5, 10, 20].map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => setLogCount(preset)}
                      className={logCount === preset ? 'preset-btn active' : 'preset-btn'}
                      disabled={busy}
                    >
                      {preset}
                    </button>
                  ))}
                </>
              ) : (
                <>
                  {[50, 250, 500, 1000].map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => setLogCount(preset)}
                      className={logCount === preset ? 'preset-btn active' : 'preset-btn'}
                      disabled={busy}
                    >
                      {preset}
                    </button>
                  ))}
                </>
              )}
            </div>
          </div>
          <span className="control-caption">
            {isDirectDefect
              ? 'Sends repeated requests to the defective endpoint.'
              : 'Number of stack trace error events sent to Kafka.'}
          </span>
        </div>

        <div className="control-group action-group">
          <div className="caution-confirm-row">
            <label htmlFor={cautionCheckboxId} className="caution-checkbox-label">
              <input
                id={cautionCheckboxId}
                type="checkbox"
                checked={confirmedCaution}
                onChange={(e) => setConfirmedCaution(e.target.checked)}
                disabled={busy}
              />
              <span>Acknowledge intentional demo fault</span>
            </label>
          </div>

          <button
            type="button"
            className="blast-button"
            disabled={busy || !confirmedCaution}
            onClick={() => void handleBlast()}
            aria-busy={busy}
          >
            {busy ? (
              <>
                <RefreshCw size={14} className="spin" aria-hidden="true" />
                <span>Injecting fault…</span>
              </>
            ) : isDirectDefect ? (
              <>
                <Flame size={14} aria-hidden="true" />
                <span>Trigger Defect ({logCount}x)</span>
              </>
            ) : (
              <>
                <Zap size={14} fill="currentColor" aria-hidden="true" />
                <span>Blast Outage ({logCount} logs)</span>
              </>
            )}
          </button>
        </div>
      </div>

      {feedback && (
        <div
          className={`chaos-feedback ${feedback.type}`}
          role={feedback.type === 'error' ? 'alert' : 'status'}
        >
          {feedback.type === 'success' ? (
            <CheckCircle2 size={15} aria-hidden="true" />
          ) : (
            <AlertCircle size={15} aria-hidden="true" />
          )}
          <span className="feedback-message">{feedback.message}</span>
          <button
            type="button"
            className="feedback-dismiss"
            onClick={() => setFeedback(null)}
            aria-label="Dismiss feedback message"
          >
            ×
          </button>
        </div>
      )}
    </section>
  )
}
