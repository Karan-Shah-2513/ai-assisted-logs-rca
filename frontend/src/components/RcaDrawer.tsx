import { useState } from 'react'
import { Check, Clipboard, GitCommitHorizontal, Sparkles, X } from 'lucide-react'
import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'
import { DiffViewer } from './DiffViewer'
import type { Incident } from '../types'

const cn = (...values: Parameters<typeof clsx>) => twMerge(clsx(...values))

interface RcaDrawerProps {
  incident: Incident
  resolving: boolean
  onClose: () => void
  onResolve: (incident: Incident) => void
  onError: (message: string) => void
}

export function RcaDrawer({ incident, resolving, onClose, onResolve, onError }: RcaDrawerProps) {
  const [copied, setCopied] = useState(false)
  const report = incident.aiRcaJson
  const confidence = report?.confidenceScore ?? 0

  const copyRemediation = async () => {
    try {
      await navigator.clipboard.writeText(report?.suggestedRemediation || '')
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1800)
    } catch {
      onError('Could not access the clipboard. Copy the command manually instead.')
    }
  }

  return (
    <div className="drawer-layer" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <aside className="rca-drawer" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <div className="drawer-topline" />
        <header className="drawer-header">
          <div>
            <div className="drawer-eyebrow"><Sparkles size={13} /> AI INCIDENT ANALYSIS</div>
            <h2 id="drawer-title">Root cause inspector</h2>
          </div>
          <button type="button" className="icon-button" aria-label="Close incident inspector" onClick={onClose}><X size={18} /></button>
        </header>
        <div className="drawer-scroll">
          <div className="drawer-incident-title">
            <span className={cn('severity-pill', `severity-${report?.severity?.toLowerCase()}`)}><i />{report?.severity || 'P2'}</span>
            <div><h3>{incident.serviceName}</h3><span>{incident.sanitizedStackTrace?.split(':')[0] || 'Runtime exception'}</span></div>
            <span className="drawer-occurrence">{incident.occurrenceCount.toLocaleString()} hits</span>
          </div>

          <section className="inspector-section">
            <div className="section-label"><span>01</span> ROOT CAUSE</div>
            <div className="root-cause-card">
              <span className="ai-orb"><Sparkles size={15} /></span>
              <p>{report?.rootCauseSummary || 'AI analysis is being generated for this incident.'}</p>
            </div>
          </section>

          <section className="inspector-section confidence-section">
            <div className="section-label"><span>02</span> MODEL CONFIDENCE</div>
            <div className="confidence-overview"><strong>{confidence}<small>%</small></strong><span>High confidence</span></div>
            <div className="progress-track"><span style={{ width: `${confidence}%` }} /></div>
            <div className="confidence-caption"><span>AI root cause certainty</span><span>{confidence >= 80 ? 'Strong signal' : 'Review suggested'}</span></div>
          </section>

          <section className="inspector-section">
            <div className="section-label"><span>03</span> SUSPECTED CHANGE</div>
            <div className="section-title"><GitCommitHorizontal size={15} /><span>Likely introducing commit</span></div>
            <DiffViewer commit={report?.suspectedCommit || ''} />
          </section>

          <section className="inspector-section">
            <div className="section-label"><span>04</span> MATCHED RUNBOOK</div>
            <div className="runbook-card"><div className="runbook-header"><span className="runbook-mark">R</span><span>OPERATIONAL PLAYBOOK</span><span className="runbook-tag">MATCHED</span></div><pre>{report?.matchedRunbook || 'No related runbook was found.'}</pre></div>
          </section>

          <section className="inspector-section">
            <div className="section-label"><span>05</span> SUGGESTED REMEDIATION</div>
            <div className="remediation-box">
              <div className="remediation-label"><span className="terminal-dot" /> ROLLBACK / REMEDIATION</div>
              <code>{report?.suggestedRemediation || 'No remediation command available.'}</code>
              <button type="button" className={cn('copy-button', copied && 'is-copied')} onClick={copyRemediation} aria-label="Copy remediation command">
                {copied ? <Check size={14} /> : <Clipboard size={14} />}{copied ? 'Copied' : 'Copy'}
              </button>
            </div>
          </section>
        </div>
        <footer className="drawer-footer">
          <button className="resolve-button" type="button" disabled={resolving || incident.status === 'RESOLVED'} onClick={() => onResolve(incident)}>
            <Check size={16} />{resolving ? 'Resolving…' : incident.status === 'RESOLVED' ? 'Resolved & in knowledge base' : 'Resolve & add to knowledge base'}
          </button>
          <span>Resolution enriches future AI analysis</span>
        </footer>
      </aside>
    </div>
  )
}
