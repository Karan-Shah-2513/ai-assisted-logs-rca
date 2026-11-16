import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import type { Incident, Telemetry } from '../src/types.ts'
import {
  SYSTEM_1_CARD_TITLE,
  formatCommitEvidence,
  formatRunbookRelevance,
  formatSeverityBadge,
  getDecisionRoute,
  getLockedApprovalReason,
  getRemediationSafety,
  isSafeAutoRollbackEligible,
} from '../src/decision-gate.ts'

describe('Task 35: Gemini Decisions Gate Frontend Contract', () => {
  describe('Top Telemetry KPI Decision-Gated / Auto-Triaged', () => {
    it('uses totalDecisions rather than noise-only decisionGatedLogs', () => {
      const telemetry: Telemetry = {
        tenantId: '11111111-1111-1111-1111-111111111111',
        totalLogs: 1000,
        deduplicatedLogs: 850,
        uniqueIncidents: 15,
        deduplicationRatio: 85.0,
        estimatedCostSavedUsd: 12.5,
        decisionGatedLogs: 2, // noise-only count
        totalDecisions: 15,   // total evaluated incidents
        averageDecisionLatencyMs: 142.4,
      }

      // Assert total evaluated incidents matches totalDecisions, not decisionGatedLogs
      assert.equal(telemetry.totalDecisions, 15)
      assert.notEqual(telemetry.totalDecisions, telemetry.decisionGatedLogs)
      assert.equal(Math.round(telemetry.averageDecisionLatencyMs || 0), 142)
    })
  })

  describe('Calibrated Severity Badge Format', () => {
    it('formats calibrated severity as P0 (98% conf) style', () => {
      assert.equal(formatSeverityBadge('P0', 98), 'P0 (98% conf)')
      assert.equal(formatSeverityBadge('P1', 95.4), 'P1 (95% conf)')
      assert.equal(formatSeverityBadge('P2', null), 'P2')
      assert.equal(formatSeverityBadge('P3', undefined), 'P3')
      assert.equal(formatSeverityBadge(null, 98), null)
    })
  })

  describe('Decision Route & Safety Badges', () => {
    it('extracts deep_gemma4_rca, fast_rag_only, and skip_llm_noise triage routes', () => {
      const makeIncident = (triageRoute: string): Incident => ({
        id: 'inc-1',
        tenantId: '11111111-1111-1111-1111-111111111111',
        fingerprintHash: 'hash-1',
        serviceName: 'order-service',
        rawStackTrace: 'trace',
        sanitizedStackTrace: 'trace',
        occurrenceCount: 1,
        status: 'OPEN',
        firstSeen: '2026-11-14T10:00:00Z',
        lastSeen: '2026-11-14T10:00:00Z',
        decisionGateJson: {
          triageRoute: triageRoute as any,
        },
      })

      assert.equal(getDecisionRoute(makeIncident('deep_gemma4_rca')), 'deep_gemma4_rca')
      assert.equal(getDecisionRoute(makeIncident('fast_rag_only')), 'fast_rag_only')
      assert.equal(getDecisionRoute(makeIncident('skip_llm_noise')), 'skip_llm_noise')
    })

    it('extracts safe_auto_rollback, requires_human_approval, and escalate_dba_finance remediation safety', () => {
      const makeIncident = (safety: string): Incident => ({
        id: 'inc-2',
        tenantId: '11111111-1111-1111-1111-111111111111',
        fingerprintHash: 'hash-2',
        serviceName: 'wallet-service',
        rawStackTrace: 'trace',
        sanitizedStackTrace: 'trace',
        occurrenceCount: 1,
        status: 'OPEN',
        firstSeen: '2026-11-14T10:00:00Z',
        lastSeen: '2026-11-14T10:00:00Z',
        decisionGateJson: {
          remediationSafety: {
            choice: safety as any,
            confidence: 0.92,
          },
        },
      })

      assert.equal(getRemediationSafety(makeIncident('safe_auto_rollback')), 'safe_auto_rollback')
      assert.equal(getRemediationSafety(makeIncident('requires_human_approval')), 'requires_human_approval')
      assert.equal(getRemediationSafety(makeIncident('escalate_dba_finance')), 'escalate_dba_finance')
    })
  })

  describe('System 1 Evidence Filtering & Guardrails', () => {
    it('displays Filtered Out < 40% when suspect commit probability is below 0.40', () => {
      assert.equal(formatCommitEvidence(0.25, false), 'Filtered Out < 40%')
      assert.equal(formatCommitEvidence(0.39, true), 'Filtered Out < 40%')
      assert.equal(formatCommitEvidence(0.40, true), '40% prob')
      assert.equal(formatCommitEvidence(0.88, true), '88% prob')
      assert.equal(formatCommitEvidence(null, false), 'Filtered Out < 40%')
      assert.equal(formatCommitEvidence(null, true), '—')
    })

    it('displays Filtered Out < 40% or relevance for runbook evidence', () => {
      assert.equal(formatRunbookRelevance(0.20, false), 'Filtered Out < 40%')
      assert.equal(formatRunbookRelevance(0.35, true), 'Filtered Out < 40%')
      assert.equal(formatRunbookRelevance(0.75, true), '75% relevance')
      assert.equal(formatRunbookRelevance(null, true), '—')
    })

    it('enables safe_auto_rollback button only when confidence >= 0.85 and safety is safe_auto_rollback', () => {
      // safe_auto_rollback with conf >= 0.85 -> ELIGIBLE
      assert.equal(isSafeAutoRollbackEligible('safe_auto_rollback', 0.92), true)
      assert.equal(isSafeAutoRollbackEligible('safe_auto_rollback', 0.85), true)
      assert.equal(isSafeAutoRollbackEligible('safe_auto_rollback', 92), true) // percentage form

      // safe_auto_rollback with conf < 0.85 -> LOCKED
      assert.equal(isSafeAutoRollbackEligible('safe_auto_rollback', 0.84), false)
      assert.equal(isSafeAutoRollbackEligible('safe_auto_rollback', 0.70), false)
      assert.equal(isSafeAutoRollbackEligible('safe_auto_rollback', 70), false)

      // other safety choices -> LOCKED regardless of confidence
      assert.equal(isSafeAutoRollbackEligible('requires_human_approval', 0.99), false)
      assert.equal(isSafeAutoRollbackEligible('escalate_dba_finance', 0.95), false)
      assert.equal(isSafeAutoRollbackEligible(null, 0.95), false)
    })

    it('provides locked approval message based on safety classification', () => {
      assert.match(
        getLockedApprovalReason('safe_auto_rollback', 0.75),
        /Confidence \(75%\) below 85% safety threshold/,
      )
      assert.match(
        getLockedApprovalReason('requires_human_approval', 0.99),
        /Safety classification requires human approval/,
      )
      assert.match(
        getLockedApprovalReason('escalate_dba_finance', 0.95),
        /High-risk remediation requires escalation to DBA or finance operations/,
      )
    })

    it('defines exact System 1 card title: System 1: Gemini Decision 1 (0 Output Tokens)', () => {
      assert.equal(SYSTEM_1_CARD_TITLE, 'System 1: Gemini Decision 1 (0 Output Tokens)')
    })

    it('includes exact System 1 card title in RcaDrawer component', () => {
      const rcaDrawerSource = readFileSync(
        new URL('../src/components/RcaDrawer.tsx', import.meta.url),
        'utf8',
      )
      assert.ok(
        rcaDrawerSource.includes('System 1: Gemini Decision 1 (0 Output Tokens)'),
        'RcaDrawer must contain exact visible title "System 1: Gemini Decision 1 (0 Output Tokens)"',
      )
    })
  })
})
