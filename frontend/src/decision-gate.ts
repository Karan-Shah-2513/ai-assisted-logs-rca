import type { Incident, RemediationSafety, Severity, TriageRoute } from './types'

export const SYSTEM_1_CARD_TITLE = 'System 1: Gemini Decision 1 (0 Output Tokens)'

/**
 * Extracts decision gate triage route (deep_gemma4_rca | fast_rag_only | skip_llm_noise)
 */
export function getDecisionRoute(incident: Incident): TriageRoute | string | null {
  const gate = incident.decisionGateJson
  if (!gate) return null
  return gate.triageRoute || gate.criteria?.triageRoute || null
}

/**
 * Extracts remediation safety classification (safe_auto_rollback | requires_human_approval | escalate_dba_finance)
 */
export function getRemediationSafety(incident: Incident): RemediationSafety | string | null {
  const gate = incident.decisionGateJson
  if (!gate) return null
  if (typeof gate.remediationSafety === 'string') return gate.remediationSafety
  if (gate.remediationSafety?.choice) return gate.remediationSafety.choice
  if (gate.answers?.remediation_safety?.choice) return gate.answers.remediation_safety.choice
  return null
}

/**
 * Formats calibrated severity label in "P0 (98% conf)" style
 */
export function formatSeverityBadge(
  severity?: Severity | string | null,
  confidence?: number | null,
): string | null {
  if (!severity) return null
  if (confidence != null && !Number.isNaN(confidence)) {
    return `${severity} (${Math.round(confidence)}% conf)`
  }
  return severity
}

/**
 * Formats suspect commit probability or evidence filter threshold "Filtered Out < 40%"
 */
export function formatCommitEvidence(commitNoul?: number | null, gitDiffIncluded?: boolean): string {
  if (commitNoul != null) {
    if (commitNoul < 0.40 || gitDiffIncluded === false) {
      return 'Filtered Out < 40%'
    }
    return `${Math.round(commitNoul * 100)}% prob`
  }
  return gitDiffIncluded === false ? 'Filtered Out < 40%' : '—'
}

/**
 * Formats runbook relevance or evidence filter threshold "Filtered Out < 40%"
 */
export function formatRunbookRelevance(runbookNoul?: number | null, runbookIncluded?: boolean): string {
  if (runbookNoul != null) {
    if (runbookNoul < 0.40 || runbookIncluded === false) {
      return 'Filtered Out < 40%'
    }
    return `${Math.round(runbookNoul * 100)}% relevance`
  }
  return '—'
}

/**
 * Checks whether safe_auto_rollback button is enabled (confidence >= 0.85 and safety is safe_auto_rollback)
 */
export function isSafeAutoRollbackEligible(
  safetyChoice?: string | null,
  safetyConfidence?: number | null,
): boolean {
  if (!safetyChoice || safetyConfidence == null) return false
  const normalizedSafety = safetyChoice.trim().toLowerCase()
  const normalizedConf = safetyConfidence > 1 ? safetyConfidence / 100 : safetyConfidence
  return normalizedSafety === 'safe_auto_rollback' && normalizedConf >= 0.85
}

/**
 * Computes locked approval reason message when auto-rollback cannot be executed
 */
export function getLockedApprovalReason(
  safetyChoice?: string | null,
  safetyConfidence?: number | null,
): string {
  const normalizedSafety = safetyChoice ? safetyChoice.trim().toLowerCase() : ''
  const normalizedConf =
    safetyConfidence != null
      ? safetyConfidence > 1
        ? safetyConfidence / 100
        : safetyConfidence
      : null

  if (normalizedSafety === 'safe_auto_rollback') {
    if (normalizedConf != null && normalizedConf < 0.85) {
      return `Confidence (${Math.round(normalizedConf * 100)}%) below 85% safety threshold for auto-rollback.`
    }
    return 'Human approval required prior to remediation.'
  }

  if (normalizedSafety === 'requires_human_approval') {
    return 'Safety classification requires human approval prior to applying mitigation.'
  }

  if (normalizedSafety === 'escalate_dba_finance') {
    return 'High-risk remediation requires escalation to DBA or finance operations.'
  }

  return 'Human approval required prior to remediation.'
}
