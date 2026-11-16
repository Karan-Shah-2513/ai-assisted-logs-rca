import { useEffect, useState, useId } from 'react'
import {
  AlertCircle,
  Check,
  CheckCircle2,
  Clipboard,
  GitCommitHorizontal,
  Info,
  KeyRound,
  Lock,
  Play,
  ShieldCheck,
  Sparkles,
  Terminal,
  X,
} from 'lucide-react'
import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'
import { DiffViewer } from './DiffViewer'
import { resolveIncident } from '../api'
import type { Incident, ResolveIncidentRequest } from '../types'
import {
  SYSTEM_1_CARD_TITLE,
  formatCommitEvidence,
  formatRunbookRelevance,
  getDecisionRoute,
  getLockedApprovalReason,
  getRemediationSafety,
  isSafeAutoRollbackEligible,
} from '../decision-gate'

const cn = (...values: Parameters<typeof clsx>) => twMerge(clsx(...values))

interface RcaDrawerProps {
  incident: Incident
  tenantId: string
  tenantName: string
  apiKey: string
  onClose: () => void
  onIncidentUpdated: (updated: Incident) => void
  onNotify: (message: string, type?: 'success' | 'warning' | 'error') => void
}

export function RcaDrawer({
  incident,
  tenantId,
  tenantName,
  apiKey,
  onClose,
  onIncidentUpdated,
  onNotify,
}: RcaDrawerProps) {
  const [copiedRemediation, setCopiedRemediation] = useState(false)
  const [copiedStackTrace, setCopiedStackTrace] = useState(false)

  // Resolution Form State
  const [verifiedRootCause, setVerifiedRootCause] = useState('')
  const [verifiedFixCommandOrPR, setVerifiedFixCommandOrPR] = useState('')
  const [resolvedBy, setResolvedBy] = useState('')
  const [resolving, setResolving] = useState(false)
  const [resolveError, setResolveError] = useState<string | null>(null)
  const [validationErrors, setValidationErrors] = useState<{
    rootCause?: string
    fixCommand?: string
    resolvedBy?: string
  }>({})

  const report = incident.aiRcaJson
  const hasAiRca = Boolean(report && report.rootCauseSummary)
  const confidence = report?.confidenceScore ?? 0
  const isResolved = incident.status === 'RESOLVED'

  // System 1 Calibrated Decision Gate (Task 35)
  const gate = incident.decisionGateJson
  const effectiveSeverity = gate?.calibratedSeverity || report?.severity || 'P2'
  const effectiveConfidence =
    gate?.calibratedConfidence != null
      ? gate.calibratedConfidence
      : report?.confidenceScore != null
      ? report.confidenceScore
      : null

  const triageRoute = getDecisionRoute(incident) || 'deep_gemma4_rca'
  const gateLatencyMs = gate?.decisionLatencyMs

  const isActionableNoul = gate?.criteria?.isActionableNoul ?? gate?.answers?.is_actionable?.noul
  const isActionableConfidence =
    gate?.criteria?.isActionableConfidence ?? gate?.answers?.is_actionable?.confidence

  const commitNoul = gate?.criteria?.commitCausedBugNoul ?? gate?.answers?.commit_caused_bug?.noul
  const gitDiffIncluded = gate?.gitDiffIncluded ?? (commitNoul != null ? commitNoul >= 0.40 : true)
  const commitDisplay = formatCommitEvidence(commitNoul, gitDiffIncluded)

  const runbookNoul = gate?.criteria?.runbookAppliesNoul ?? gate?.answers?.runbook_applies?.noul
  const runbookIncluded =
    gate?.runbookContextIncluded ?? (runbookNoul != null ? runbookNoul >= 0.40 : true)
  const runbookDisplay = formatRunbookRelevance(runbookNoul, runbookIncluded)

  const safetyChoice = getRemediationSafety(incident) || 'requires_human_approval'

  const rawSafetyConfidence =
    gate?.remediationSafety?.confidence ?? gate?.answers?.remediation_safety?.confidence
  const safetyConfidence =
    rawSafetyConfidence != null
      ? rawSafetyConfidence > 1
        ? rawSafetyConfidence / 100
        : rawSafetyConfidence
      : gate?.calibratedConfidence != null
      ? gate.calibratedConfidence / 100
      : null

  const canAutoRollback = isSafeAutoRollbackEligible(safetyChoice, safetyConfidence)
  const lockedApprovalReason = getLockedApprovalReason(safetyChoice, safetyConfidence)

  const handleGuardedRollback = () => {
    onNotify(
      'Guarded UI affordance: automated rollback execution is non-operational in this environment. No rollback was executed.',
      'warning',
    )
  }

  const rootCauseFieldId = useId()
  const fixCommandFieldId = useId()
  const resolvedByFieldId = useId()

  // Close on Escape key
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  const copyRemediation = async () => {
    if (!report?.suggestedRemediation) return
    try {
      await navigator.clipboard.writeText(report.suggestedRemediation)
      setCopiedRemediation(true)
      window.setTimeout(() => setCopiedRemediation(false), 2000)
    } catch {
      onNotify('Could not access clipboard. Please copy manually.', 'warning')
    }
  }

  const copyStackTrace = async () => {
    const trace = incident.sanitizedStackTrace || incident.rawStackTrace
    if (!trace) return
    try {
      await navigator.clipboard.writeText(trace)
      setCopiedStackTrace(true)
      window.setTimeout(() => setCopiedStackTrace(false), 2000)
    } catch {
      onNotify('Could not access clipboard. Please copy manually.', 'warning')
    }
  }

  // Pre-fill helpers for resolution form
  const prefillFromAiRca = () => {
    if (report?.rootCauseSummary) {
      setVerifiedRootCause(report.rootCauseSummary)
      setValidationErrors((prev) => ({ ...prev, rootCause: undefined }))
    }
  }

  const prefillFromAiRemediation = () => {
    if (report?.suggestedRemediation) {
      setVerifiedFixCommandOrPR(report.suggestedRemediation)
      setValidationErrors((prev) => ({ ...prev, fixCommand: undefined }))
    }
  }

  const handleResolveSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setResolveError(null)

    // Form client-side validation for contract: nonblank fields
    const errors: { rootCause?: string; fixCommand?: string; resolvedBy?: string } = {}
    if (!verifiedRootCause.trim()) {
      errors.rootCause = 'Verified root cause is required and must not be blank'
    } else if (verifiedRootCause.length > 4000) {
      errors.rootCause = 'Verified root cause must not exceed 4,000 characters'
    }

    if (!verifiedFixCommandOrPR.trim()) {
      errors.fixCommand = 'Verified fix command or PR is required and must not be blank'
    } else if (verifiedFixCommandOrPR.length > 4000) {
      errors.fixCommand = 'Verified fix command must not exceed 4,000 characters'
    }

    if (!resolvedBy.trim()) {
      errors.resolvedBy = 'Resolved by (engineer handle/email) is required'
    } else if (resolvedBy.length > 200) {
      errors.resolvedBy = 'Resolved by must not exceed 200 characters'
    }

    if (Object.keys(errors).length > 0) {
      setValidationErrors(errors)
      return
    }

    if (!apiKey || !apiKey.trim()) {
      setResolveError(
        `X-API-Key is required to authorize resolution for ${tenantName}. Please configure an API key in the top bar.`,
      )
      return
    }

    setResolving(true)
    try {
      const payload: ResolveIncidentRequest = {
        verifiedRootCause: verifiedRootCause.trim(),
        verifiedFixCommandOrPR: verifiedFixCommandOrPR.trim(),
        resolvedBy: resolvedBy.trim(),
      }

      const updated = await resolveIncident(tenantId, incident.id, payload, apiKey)
      onIncidentUpdated(updated)
      onNotify(`Incident ${incident.id.slice(0, 8)}… resolved and indexed into vector knowledge base!`, 'success')
    } catch (err: unknown) {
      const axiosErr = err as {
        response?: { status: number; data?: { message?: string } }
        message?: string
      }
      const status = axiosErr.response?.status
      const detail = axiosErr.response?.data?.message || axiosErr.message || 'Unknown network error'

      if (status === 401) {
        setResolveError(`401 Unauthorized: Invalid X-API-Key for ${tenantName}.`)
      } else if (status === 403) {
        setResolveError(`403 Forbidden: X-Tenant-ID does not match authenticated key credentials.`)
      } else {
        setResolveError(`Resolution failed: ${detail}`)
      }
    } finally {
      setResolving(false)
    }
  }

  return (
    <div
      className="drawer-layer"
      role="presentation"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <aside
        className="rca-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="drawer-title"
      >
        <div className="drawer-topline" />

        <header className="drawer-header">
          <div>
            <div className="drawer-eyebrow">
              <Sparkles size={13} aria-hidden="true" />
              <span>AI RCA INSPECTOR & RESOLUTION</span>
            </div>
            <h2 id="drawer-title">Incident Analysis</h2>
          </div>
          <button
            type="button"
            className="icon-button"
            aria-label="Close incident inspector"
            onClick={onClose}
          >
            <X size={18} />
          </button>
        </header>

        <div className="drawer-scroll">
          {/* Incident identity banner */}
          <div className="drawer-incident-title">
            <span
              className={cn(
                'severity-pill',
                effectiveSeverity
                  ? `severity-${effectiveSeverity.toLowerCase()}`
                  : 'severity-p2',
              )}
            >
              <i aria-hidden="true" />
              {effectiveConfidence != null
                ? `${effectiveSeverity} (${Math.round(effectiveConfidence)}% conf)`
                : effectiveSeverity}
            </span>

            <div className="drawer-service-info">
              <h3>{incident.serviceName}</h3>
              <span className="drawer-hash" title={`Fingerprint: ${incident.fingerprintHash}`}>
                Hash: <code>{incident.fingerprintHash.slice(0, 16)}…</code>
              </span>
            </div>

            <div className="drawer-meta-badges">
              <span className={`status-pill status-${incident.status.toLowerCase()}`}>
                <i aria-hidden="true" />
                {incident.status.toLowerCase()}
              </span>
              <span className="drawer-occurrence">
                {incident.occurrenceCount.toLocaleString()} events
              </span>
            </div>
          </div>

          {/* System 1: Calibrated Decision Gate Card (Task 35) */}
          <section className="inspector-section system1-section" aria-label="System 1 calibrated decision gate">
            <div className="section-label">
              <span>01</span>
              <span>SYSTEM 1: GEMINI DECISION 1 (0 OUTPUT TOKENS)</span>
            </div>

            <div className="system1-card">
              <div className="system1-card-header">
                <div className="system1-title-group">
                  <ShieldCheck size={14} className="system1-badge-icon" aria-hidden="true" />
                  <span className="system1-card-title">{SYSTEM_1_CARD_TITLE || 'System 1: Gemini Decision 1 (0 Output Tokens)'}</span>
                  <span className="system1-model-tag">gemini-decision-1</span>
                  {triageRoute && (
                    <span className={`route-pill route-${triageRoute.toLowerCase()}`}>
                      {triageRoute}
                    </span>
                  )}
                </div>
                {gateLatencyMs != null && (
                  <span className="system1-latency-badge">
                    {gateLatencyMs}ms gate latency
                  </span>
                )}
              </div>

              <div className="system1-metrics-grid">
                <div className="system1-metric-item">
                  <span className="metric-label">Actionable Prob / Conf</span>
                  <strong className="metric-value">
                    {isActionableNoul != null
                      ? `${Math.round(isActionableNoul * 100)}% prob${
                          isActionableConfidence != null
                            ? ` (${Math.round(isActionableConfidence * 100)}% conf)`
                            : ''
                        }`
                      : '—'}
                  </strong>
                </div>

                <div className="system1-metric-item">
                  <span className="metric-label">Suspect Commit</span>
                  <strong className={cn('metric-value', commitDisplay.includes('Filtered Out') && 'text-amber')}>
                    {commitDisplay}
                  </strong>
                </div>

                <div className="system1-metric-item">
                  <span className="metric-label">Runbook Relevance</span>
                  <strong className="metric-value">
                    {runbookDisplay}
                  </strong>
                </div>

                <div className="system1-metric-item">
                  <span className="metric-label">Remediation Safety</span>
                  <div className="metric-value-wrap">
                    <span
                      className={`safety-pill safety-${safetyChoice.toLowerCase()}`}
                      title={`Safety: ${safetyChoice}`}
                    >
                      <i aria-hidden="true" />
                      {safetyChoice}
                    </span>
                    {safetyConfidence != null && (
                      <span className="safety-conf-text">
                        {Math.round(safetyConfidence * 100)}% conf
                      </span>
                    )}
                  </div>
                </div>
              </div>

              {/* Guardrail control: Confidence >= 0.85 safe_auto_rollback button or locked approval message */}
              <div className="system1-guardrail-row">
                {canAutoRollback ? (
                  <div className="system1-action-box">
                    <button
                      type="button"
                      className="system1-rollback-btn"
                      onClick={handleGuardedRollback}
                      title="Guarded UI affordance: Non-execution mode"
                    >
                      <Play size={11} fill="currentColor" aria-hidden="true" />
                      <span>Safe Auto-Rollback (Guarded)</span>
                    </button>
                    <span className="system1-guardrail-caption">
                      Guarded affordance · Conf: {Math.round((safetyConfidence ?? 0) * 100)}% (threshold ≥ 85%)
                    </span>
                  </div>
                ) : (
                  <div className="system1-locked-banner" role="status">
                    <Lock size={12} className="locked-icon" aria-hidden="true" />
                    <span className="locked-message">
                      Approval locked: {lockedApprovalReason}
                    </span>
                  </div>
                )}
              </div>
            </div>
          </section>

          {/* Section 2: AI Root Cause Analysis (System 2 Gemma RCA) */}
          <section className="inspector-section" aria-label="Root cause analysis">
            <div className="section-label">
              <span>02</span>
              <span>SYSTEM 2 GEMMA RCA</span>
            </div>

            {hasAiRca ? (
              <>
                <div className="root-cause-card">
                  <span className="ai-orb" aria-hidden="true">
                    <Sparkles size={15} />
                  </span>
                  <p>{report?.rootCauseSummary}</p>
                </div>

                {/* Model Confidence */}
                <div className="confidence-overview-card">
                  <div className="confidence-overview">
                    <strong>
                      {confidence}
                      <small>%</small>
                    </strong>
                    <span>
                      {confidence >= 90
                        ? 'High confidence signal'
                        : confidence >= 70
                        ? 'Moderate confidence signal'
                        : 'Review suggested'}
                    </span>
                  </div>
                  <div className="progress-track" aria-hidden="true">
                    <span style={{ width: `${Math.min(100, Math.max(0, confidence))}%` }} />
                  </div>
                  <div className="confidence-caption">
                    <span>Gemini + PgVector similarity score</span>
                    <span>{confidence >= 80 ? 'Strong correlation' : 'Low match score'}</span>
                  </div>
                </div>
              </>
            ) : (
              <div className="honest-empty-rca" role="note">
                <Info size={16} className="honest-icon" aria-hidden="true" />
                <div className="honest-copy">
                  <strong>AI RCA Not Available</strong>
                  <p>
                    No automated root cause report has been generated for this incident yet.
                    Background vector retrieval may be in progress, or AI fallback was engaged.
                    Inspect the diagnostics below and verify resolution directly.
                  </p>
                </div>
              </div>
            )}
          </section>

          {/* Section 3: Suspected Commit Diff (when available) */}
          {hasAiRca && report?.suspectedCommit && (
            <section className="inspector-section" aria-label="Suspected git change">
              <div className="section-label">
                <span>03</span>
                <span>SUSPECTED CHANGE</span>
              </div>
              <div className="section-title">
                <GitCommitHorizontal size={15} aria-hidden="true" />
                <span>Suspected Introducing Commit</span>
              </div>
              <DiffViewer commit={report.suspectedCommit} />
            </section>
          )}

          {/* Section 4: Matched Operational Runbook (when available) */}
          {hasAiRca && report?.matchedRunbook && (
            <section className="inspector-section" aria-label="Matched runbook">
              <div className="section-label">
                <span>04</span>
                <span>MATCHED RUNBOOK</span>
              </div>
              <div className="runbook-card">
                <div className="runbook-header">
                  <span className="runbook-mark" aria-hidden="true">
                    R
                  </span>
                  <span>OPERATIONAL PLAYBOOK</span>
                  <span className="runbook-tag">VECTOR RETRIEVED</span>
                </div>
                <pre>{report.matchedRunbook}</pre>
              </div>
            </section>
          )}

          {/* Section 5: Suggested Remediation (when available) */}
          {hasAiRca && report?.suggestedRemediation && (
            <section className="inspector-section" aria-label="Suggested remediation">
              <div className="section-label">
                <span>05</span>
                <span>SUGGESTED REMEDIATION</span>
              </div>
              <div className="remediation-box">
                <div className="remediation-label">
                  <span className="terminal-dot" aria-hidden="true" />
                  <span>REMEDIATION / ROLLBACK</span>
                </div>
                <code>{report.suggestedRemediation}</code>
                <button
                  type="button"
                  className={cn('copy-button', copiedRemediation && 'is-copied')}
                  onClick={() => void copyRemediation()}
                  aria-label="Copy remediation command"
                >
                  {copiedRemediation ? <Check size={13} /> : <Clipboard size={13} />}
                  <span>{copiedRemediation ? 'Copied' : 'Copy'}</span>
                </button>
              </div>
            </section>
          )}

          {/* Section 6: Sanitized Stack Trace Diagnostics */}
          <section className="inspector-section" aria-label="Stack trace diagnostics">
            <div className="section-label">
              <span>{hasAiRca ? '06' : '02'}</span>
              <span>FAILURE SIGNATURE & STACK TRACE</span>
            </div>
            <div className="stack-trace-box">
              <div className="stack-trace-header">
                <div className="trace-header-left">
                  <Terminal size={12} aria-hidden="true" />
                  <span>Sanitized Exception Signature</span>
                </div>
                <button
                  type="button"
                  className={cn('trace-copy-btn', copiedStackTrace && 'copied')}
                  onClick={() => void copyStackTrace()}
                  aria-label="Copy stack trace"
                >
                  {copiedStackTrace ? <Check size={11} /> : <Clipboard size={11} />}
                  <span>{copiedStackTrace ? 'Copied' : 'Copy'}</span>
                </button>
              </div>
              <pre className="stack-trace-code">
                {incident.sanitizedStackTrace || incident.rawStackTrace || 'No stack trace captured.'}
              </pre>
            </div>
          </section>

          {/* Section 7: Verified Incident Resolution Form (Task 12 & Task 22) */}
          <section className="inspector-section resolution-section" aria-label="Incident resolution form">
            <div className="section-label">
              <span>{hasAiRca ? '07' : '03'}</span>
              <span>HUMAN-VERIFIED RESOLUTION</span>
            </div>

            {isResolved ? (
              <div className="resolved-status-banner" role="status">
                <CheckCircle2 size={18} className="resolved-icon" aria-hidden="true" />
                <div className="resolved-copy">
                  <strong>Incident Resolved & Indexed</strong>
                  <p>
                    This incident has been verified and marked as RESOLVED. Its verified root cause,
                    remediation command, and failure signature have been indexed into the vector
                    store (pgvector) to assist future incident triage.
                  </p>
                </div>
              </div>
            ) : (
              <form onSubmit={handleResolveSubmit} className="resolution-form" noValidate>
                <div className="resolution-form-header">
                  <p className="resolution-desc">
                    Contract endpoint <code>POST /api/v1/incidents/{incident.id.slice(0, 8)}…/resolve</code> requires
                    human-verified root cause, fix command or PR, and engineer identifier.
                  </p>
                </div>

                {/* Verified Root Cause */}
                <div className="resolution-field">
                  <div className="field-header">
                    <label htmlFor={rootCauseFieldId} className="field-label">
                      Verified Root Cause <span className="required-star">*</span>
                    </label>
                    {hasAiRca && (
                      <button
                        type="button"
                        onClick={prefillFromAiRca}
                        className="prefill-btn"
                        title="Prefill with AI Root Cause summary for human review"
                      >
                        <Sparkles size={10} aria-hidden="true" />
                        <span>Use AI Summary</span>
                      </button>
                    )}
                  </div>
                  <textarea
                    id={rootCauseFieldId}
                    rows={3}
                    maxLength={4000}
                    value={verifiedRootCause}
                    onChange={(e) => {
                      setVerifiedRootCause(e.target.value)
                      if (validationErrors.rootCause) {
                        setValidationErrors((prev) => ({ ...prev, rootCause: undefined }))
                      }
                    }}
                    placeholder="Enter human-verified technical root cause description..."
                    className={cn('resolution-textarea', validationErrors.rootCause && 'has-error')}
                    disabled={resolving}
                    aria-invalid={Boolean(validationErrors.rootCause)}
                    aria-describedby={validationErrors.rootCause ? `${rootCauseFieldId}-error` : undefined}
                  />
                  {validationErrors.rootCause && (
                    <span id={`${rootCauseFieldId}-error`} className="field-error-msg" role="alert">
                      {validationErrors.rootCause}
                    </span>
                  )}
                </div>

                {/* Verified Fix Command or PR */}
                <div className="resolution-field">
                  <div className="field-header">
                    <label htmlFor={fixCommandFieldId} className="field-label">
                      Verified Fix Command or PR Link <span className="required-star">*</span>
                    </label>
                    {hasAiRca && report?.suggestedRemediation && (
                      <button
                        type="button"
                        onClick={prefillFromAiRemediation}
                        className="prefill-btn"
                        title="Prefill with AI remediation command for human review"
                      >
                        <Sparkles size={10} aria-hidden="true" />
                        <span>Use AI Remediation</span>
                      </button>
                    )}
                  </div>
                  <textarea
                    id={fixCommandFieldId}
                    rows={2}
                    maxLength={4000}
                    value={verifiedFixCommandOrPR}
                    onChange={(e) => {
                      setVerifiedFixCommandOrPR(e.target.value)
                      if (validationErrors.fixCommand) {
                        setValidationErrors((prev) => ({ ...prev, fixCommand: undefined }))
                      }
                    }}
                    placeholder="e.g. kubectl rollout undo deployment/payment-service -n alpha-prod or PR #104"
                    className={cn('resolution-textarea', validationErrors.fixCommand && 'has-error')}
                    disabled={resolving}
                    aria-invalid={Boolean(validationErrors.fixCommand)}
                    aria-describedby={validationErrors.fixCommand ? `${fixCommandFieldId}-error` : undefined}
                  />
                  {validationErrors.fixCommand && (
                    <span id={`${fixCommandFieldId}-error`} className="field-error-msg" role="alert">
                      {validationErrors.fixCommand}
                    </span>
                  )}
                </div>

                {/* Resolved By */}
                <div className="resolution-field">
                  <label htmlFor={resolvedByFieldId} className="field-label">
                    Resolved By (Engineer / On-Call Handle) <span className="required-star">*</span>
                  </label>
                  <input
                    id={resolvedByFieldId}
                    type="text"
                    maxLength={200}
                    value={resolvedBy}
                    onChange={(e) => {
                      setResolvedBy(e.target.value)
                      if (validationErrors.resolvedBy) {
                        setValidationErrors((prev) => ({ ...prev, resolvedBy: undefined }))
                      }
                    }}
                    placeholder="e.g. alice@alphafin.com or sre-oncall"
                    className={cn('resolution-input', validationErrors.resolvedBy && 'has-error')}
                    disabled={resolving}
                    aria-invalid={Boolean(validationErrors.resolvedBy)}
                    aria-describedby={validationErrors.resolvedBy ? `${resolvedByFieldId}-error` : undefined}
                  />
                  {validationErrors.resolvedBy && (
                    <span id={`${resolvedByFieldId}-error`} className="field-error-msg" role="alert">
                      {validationErrors.resolvedBy}
                    </span>
                  )}
                </div>

                {/* API Key Missing Notice */}
                {!apiKey && (
                  <div className="resolution-auth-warning" role="alert">
                    <KeyRound size={13} aria-hidden="true" />
                    <span>
                      Missing X-API-Key for {tenantName}. Please configure the tenant key in the header to submit resolution.
                    </span>
                  </div>
                )}

                {/* Submission Error Alert */}
                {resolveError && (
                  <div className="resolution-error-alert" role="alert">
                    <AlertCircle size={14} aria-hidden="true" />
                    <span>{resolveError}</span>
                  </div>
                )}

                <button
                  type="submit"
                  disabled={resolving || !apiKey}
                  className="resolution-submit-button"
                  aria-busy={resolving}
                >
                  {resolving ? (
                    <>
                      <span className="loading-spinner-sm" aria-hidden="true" />
                      <span>Submitting Resolution to Knowledge Base…</span>
                    </>
                  ) : (
                    <>
                      <Check size={15} aria-hidden="true" />
                      <span>Verify & Resolve Incident</span>
                    </>
                  )}
                </button>
              </form>
            )}
          </section>
        </div>

        <footer className="drawer-footer">
          <div className="drawer-footer-content">
            <span className="drawer-footer-note">
              Resolving indexes human-verified patterns into pgvector for multi-tenant RAG retrieval.
            </span>
          </div>
        </footer>
      </aside>
    </div>
  )
}
