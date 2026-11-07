import type { Incident, Tenant, Telemetry } from './types'

export const tenants: Tenant[] = [
  { id: '11111111-1111-1111-1111-111111111111', name: 'AlphaFin Corp' },
  { id: '22222222-2222-2222-2222-222222222222', name: 'BetaPay Systems' },
]

const now = Date.now()
const ago = (minutes: number) => new Date(now - minutes * 60_000).toISOString()

export const seedIncidents: Incident[] = [
  {
    id: '3fa85f64-5717-4562-b3fc-2c963f66afa6',
    tenantId: tenants[0].id,
    fingerprintHash: 'e3b0c44298fc1c149afbf4c8996fb924',
    serviceName: 'payment-service',
    rawStackTrace: 'java.sql.SQLException: Connection pool exhausted at com.airca.payment.RefundService.processBatch(RefundService.java:87)',
    sanitizedStackTrace: 'java.sql.SQLException: Connection pool exhausted at com.airca.payment.RefundService.processBatch(RefundService.java:87)',
    occurrenceCount: 501,
    status: 'INVESTIGATING',
    aiRcaJson: {
      rootCauseSummary: 'Connection pool deadlock caused by unclosed prepared statements in the batch refund loop. The pool reaches its limit under concurrent payment retries.',
      suspectedCommit: "a9f120e — refactor: batch refunds\nby alice@alphafin.com\n\n--- a/RefundService.java\n+++ b/RefundService.java\n@@ -42,6 +42,8 @@ public void processBatch(List<Refund> refunds) {\n+  connection.setAutoCommit(false);\n+  statement.executeBatch();",
      matchedRunbook: 'RB-PAY-01 · Payment Gateway Timeout & DB Deadlock\n\n1. Check active connections: SELECT count(*) FROM pg_stat_activity;\n2. Look for idle-in-transaction sessions older than 5 minutes.\n3. If pool saturation persists, roll back the latest payment-service deployment.',
      confidenceScore: 94,
      severity: 'P0',
      suggestedRemediation: 'kubectl rollout undo deployment/payment-service -n alpha-prod',
    },
    firstSeen: ago(18),
    lastSeen: ago(1),
  },
  {
    id: '7cbb4f95-97ae-4d01-a846-5217319e0021',
    tenantId: tenants[0].id,
    fingerprintHash: '87bd4a8f3c6d6d3ad1a72b45',
    serviceName: 'identity-gateway',
    rawStackTrace: 'java.lang.NullPointerException: Cannot invoke "User.getId()" because "user" is null',
    sanitizedStackTrace: 'java.lang.NullPointerException in AuthTokenFilter.validateSession(AuthTokenFilter.java:143)',
    occurrenceCount: 128,
    status: 'OPEN',
    aiRcaJson: {
      rootCauseSummary: 'A stale session token references a deleted user record. The auth filter dereferences the missing user before returning an unauthorized response.',
      suspectedCommit: 'c41b1dd — harden session refresh\nby mchen@alphafin.com\n\n--- a/AuthTokenFilter.java\n+++ b/AuthTokenFilter.java\n@@ -140,3 +140,4 @@\n+  return user.getId();',
      matchedRunbook: 'RB-AUTH-04 · Invalid Session & Identity Lookup\n\nValidate user existence before refreshing claims. Expire the session and return HTTP 401 when lookup is empty.',
      confidenceScore: 87,
      severity: 'P1',
      suggestedRemediation: 'kubectl rollout undo deployment/identity-gateway -n alpha-prod',
    },
    firstSeen: ago(42),
    lastSeen: ago(3),
  },
  {
    id: '4a0932d0-0057-4e8a-b2a3-c9bd12382288',
    tenantId: tenants[0].id,
    fingerprintHash: '36a5ce8eb3b7c73f8d3b44ca',
    serviceName: 'session-cache',
    rawStackTrace: 'redis.exceptions.OutOfMemoryError: command not allowed when used memory > maxmemory',
    sanitizedStackTrace: 'redis.exceptions.OutOfMemoryError: maxmemory limit reached',
    occurrenceCount: 76,
    status: 'OPEN',
    aiRcaJson: {
      rootCauseSummary: 'Redis maxmemory is reached because expired session keys are not being evicted. The cache eviction policy is configured as noeviction.',
      suspectedCommit: 'f018ca2 — increase session ttl\nby jrivera@alphafin.com\n\n--- a/redis.conf\n+++ b/redis.conf\n@@ -22,1 +22,1 @@\n-maxmemory-policy allkeys-lru\n+maxmemory-policy noeviction',
      matchedRunbook: 'RB-CACHE-02 · Redis Memory Pressure\n\nInspect INFO memory and eviction policy. Restore allkeys-lru and verify TTLs on session keys.',
      confidenceScore: 91,
      severity: 'P2',
      suggestedRemediation: 'kubectl rollout undo deployment/session-cache -n alpha-prod',
    },
    firstSeen: ago(75),
    lastSeen: ago(8),
  },
  {
    id: '5eb4bf66-5315-4db4-b4ac-a6dad64ea118',
    tenantId: tenants[0].id,
    fingerprintHash: '91d2e825410d',
    serviceName: 'ledger-worker',
    rawStackTrace: 'org.apache.kafka.common.errors.TimeoutException: Expiring 4 record(s) for ledger-events',
    sanitizedStackTrace: 'Kafka TimeoutException: ledger-events publish deadline exceeded',
    occurrenceCount: 24,
    status: 'RESOLVED',
    aiRcaJson: {
      rootCauseSummary: 'A short broker rebalance temporarily exceeded the worker publish timeout. The consumer group has recovered and lag is back to normal.',
      suspectedCommit: '2b6d101 — tune ledger consumer\nby ops@alphafin.com\n\n--- a/application.yml\n+++ b/application.yml\n@@ -17,1 +17,1 @@\n-  request-timeout: 3000\n+  request-timeout: 5000',
      matchedRunbook: 'RB-KAFKA-03 · Consumer Lag & Publish Timeouts\n\nConfirm group stability and verify that partition lag returns to baseline.',
      confidenceScore: 82,
      severity: 'P3',
      suggestedRemediation: 'No action required — monitor consumer lag for 15 minutes.',
    },
    firstSeen: ago(133),
    lastSeen: ago(27),
  },
]

export const seedTelemetry: Telemetry = {
  tenantId: tenants[0].id,
  totalLogs: 24_891,
  deduplicatedLogs: 24_863,
  uniqueIncidents: 28,
  deduplicationRatio: 99.9,
  estimatedCostSavedUsd: 372.95,
}
