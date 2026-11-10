import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  apiClient,
  buildTenantHeaders,
  resolveIncident,
  simulateOutage,
  triggerTenantDefect,
  fetchIncident,
} from '../src/api.ts'
import {
  ALPHAFIN_TENANT_ID,
  BETAPAY_TENANT_ID,
  getDevComposeKey,
  _setDevEnvForTesting,
} from '../src/auth.ts'
import {
  ALL_TRIGGER_OPTIONS,
  CHAOS_SCENARIOS,
} from '../src/triggers.ts'
import type { Incident, ResolveIncidentRequest } from '../src/types.ts'

describe('Task 22: Trigger & Burst Controls Contract', () => {
  it('defines valid ChaosSimulator scenarios matching backend ChaosSimulatorService', () => {
    assert.deepEqual(CHAOS_SCENARIOS, ['PAYMENT_DB_DEADLOCK', 'REDIS_OOM', 'AUTH_NPE'])

    // Every chaos simulation trigger option has a valid scenario in CHAOS_SCENARIOS
    const chaosOptions = ALL_TRIGGER_OPTIONS.filter((t) => t.category === 'CHAOS_SIMULATION')
    assert.equal(chaosOptions.length, 3)
    for (const opt of chaosOptions) {
      assert.ok(opt.scenario)
      assert.ok(CHAOS_SCENARIOS.includes(opt.scenario))
    }
  })

  it('defines tenant-scoped defect endpoints for all four microservices', () => {
    const alphafinDefects = ALL_TRIGGER_OPTIONS.filter(
      (t) => t.category === 'TENANT_DEFECT' && t.tenantScope === ALPHAFIN_TENANT_ID,
    )
    const betapayDefects = ALL_TRIGGER_OPTIONS.filter(
      (t) => t.category === 'TENANT_DEFECT' && t.tenantScope === BETAPAY_TENANT_ID,
    )

    // AlphaFin has order-service and ledger-service defect endpoints
    assert.equal(alphafinDefects.length, 4)
    const alphaServices = new Set(alphafinDefects.map((d) => d.serviceName))
    assert.ok(alphaServices.has('order-service'))
    assert.ok(alphaServices.has('ledger-service'))

    // BetaPay has auth-service and wallet-service defect endpoints
    assert.equal(betapayDefects.length, 4)
    const betaServices = new Set(betapayDefects.map((d) => d.serviceName))
    assert.ok(betaServices.has('auth-service'))
    assert.ok(betaServices.has('wallet-service'))
  })

  it('builds contract-valid headers with X-Tenant-ID and X-API-Key', () => {
    const headers = buildTenantHeaders(ALPHAFIN_TENANT_ID, 'test-key-123')
    assert.equal(headers['X-Tenant-ID'], ALPHAFIN_TENANT_ID)
    assert.equal(headers['X-API-Key'], 'test-key-123')
  })

  it('simulateOutage passes correct payload and headers to POST /api/v1/simulate-outage', async () => {
    let capturedUrl = ''
    let capturedData: unknown = null
    let capturedConfig: unknown = null

    const originalPost = apiClient.post
    // @ts-expect-error mock assignment
    apiClient.post = async (url: string, data: unknown, config: unknown) => {
      capturedUrl = url
      capturedData = data
      capturedConfig = config
      return {
        status: 202,
        data: { message: 'Chaos simulation triggered', scenario: 'PAYMENT_DB_DEADLOCK', logsQueued: 500 },
      }
    }

    try {
      const response = await simulateOutage(
        ALPHAFIN_TENANT_ID,
        'PAYMENT_DB_DEADLOCK',
        500,
        'alphafin-key',
      )

      assert.equal(capturedUrl, '/simulate-outage')
      assert.deepEqual(capturedData, {
        tenantId: ALPHAFIN_TENANT_ID,
        scenario: 'PAYMENT_DB_DEADLOCK',
        logCount: 500,
      })
      const config = capturedConfig as { headers: Record<string, string> }
      assert.equal(config.headers['X-Tenant-ID'], ALPHAFIN_TENANT_ID)
      assert.equal(config.headers['X-API-Key'], 'alphafin-key')
      assert.equal(response.logsQueued, 500)
    } finally {
      apiClient.post = originalPost
    }
  })

  it('triggerTenantDefect handles expected 500 responses as successful defect shipment', async () => {
    let callCount = 0
    let lastUrl = ''

    const originalPost = apiClient.post
    // @ts-expect-error mock assignment
    apiClient.post = async (url: string) => {
      callCount++
      lastUrl = url
      // Simulate tenant microservice throwing intentional unhandled exception (HTTP 500)
      const err = new Error('Request failed with status code 500')
      // @ts-expect-error test-only axios response simulation
      err.response = { status: 500, data: { error: 'NullPointerException' } }
      throw err
    }

    try {
      const result = await triggerTenantDefect(
        'alphafin-order-checkout',
        3,
        ALPHAFIN_TENANT_ID,
        'test-key',
      )

      assert.equal(callCount, 3)
      assert.equal(lastUrl, '/orders/checkout')
      assert.equal(result.success, true)
      assert.equal(result.serviceName, 'order-service')
      assert.ok(result.message.includes('exception response observed and ingestion attempted'))
      assert.equal(result.attemptedCount, 3)
      assert.equal(result.shippedCount, 3)
      assert.equal(result.failedCount, 0)
    } finally {
      apiClient.post = originalPost
    }
  })
})

describe('Task 22: Verified Incident Resolution Contract', () => {
  it('rejects blank or whitespace-only resolution fields before HTTP call', async () => {
    const invalidPayloads: ResolveIncidentRequest[] = [
      { verifiedRootCause: '', verifiedFixCommandOrPR: 'kubectl rollout undo', resolvedBy: 'alice' },
      { verifiedRootCause: '   ', verifiedFixCommandOrPR: 'kubectl rollout undo', resolvedBy: 'alice' },
      { verifiedRootCause: 'Valid cause', verifiedFixCommandOrPR: '', resolvedBy: 'alice' },
      { verifiedRootCause: 'Valid cause', verifiedFixCommandOrPR: '   ', resolvedBy: 'alice' },
      { verifiedRootCause: 'Valid cause', verifiedFixCommandOrPR: 'kubectl rollout undo', resolvedBy: '' },
      { verifiedRootCause: 'Valid cause', verifiedFixCommandOrPR: 'kubectl rollout undo', resolvedBy: '   ' },
    ]

    for (const payload of invalidPayloads) {
      await assert.rejects(
        async () => {
          await resolveIncident(ALPHAFIN_TENANT_ID, 'inc-123', payload, 'key')
        },
        (err: Error) => {
          assert.ok(err.message.includes('must not be blank'))
          return true
        },
      )
    }
  })

  it('submits contract-valid resolution request with headers to POST /api/v1/incidents/{id}/resolve', async () => {
    let capturedUrl = ''
    let capturedData: unknown = null
    let capturedHeaders: Record<string, string> = {}

    const originalPost = apiClient.post
    // @ts-expect-error mock assignment
    apiClient.post = async (url: string, data: unknown, config: unknown) => {
      capturedUrl = url
      capturedData = data
      const cfg = config as { headers: Record<string, string> }
      capturedHeaders = cfg.headers
      const resolvedIncident: Incident = {
        id: 'inc-12345',
        tenantId: ALPHAFIN_TENANT_ID,
        fingerprintHash: 'hash-abc',
        serviceName: 'payment-service',
        rawStackTrace: 'stack trace',
        sanitizedStackTrace: 'sanitized trace',
        occurrenceCount: 50,
        status: 'RESOLVED',
        aiRcaJson: null,
        firstSeen: '2026-11-10T12:00:00Z',
        lastSeen: '2026-11-10T12:30:00Z',
      }
      return { status: 200, data: resolvedIncident }
    }

    try {
      const payload: ResolveIncidentRequest = {
        verifiedRootCause: '   Connection pool leak in batch refund loop  ',
        verifiedFixCommandOrPR: '  kubectl rollout undo deployment/payment-service -n alpha-prod  ',
        resolvedBy: '  alice@alphafin.com  ',
      }

      const result = await resolveIncident(ALPHAFIN_TENANT_ID, 'inc-12345', payload, 'valid-api-key')

      assert.equal(capturedUrl, '/incidents/inc-12345/resolve')
      assert.deepEqual(capturedData, {
        verifiedRootCause: 'Connection pool leak in batch refund loop',
        verifiedFixCommandOrPR: 'kubectl rollout undo deployment/payment-service -n alpha-prod',
        resolvedBy: 'alice@alphafin.com',
      })
      assert.equal(capturedHeaders['X-Tenant-ID'], ALPHAFIN_TENANT_ID)
      assert.equal(capturedHeaders['X-API-Key'], 'valid-api-key')
      assert.equal(result.status, 'RESOLVED')
    } finally {
      apiClient.post = originalPost
    }
  })

  it('fetchIncident sends X-Tenant-ID and X-API-Key headers to GET /api/v1/incidents/{id}', async () => {
    let capturedUrl = ''
    let capturedHeaders: Record<string, string> = {}

    const originalGet = apiClient.get
    // @ts-expect-error mock assignment
    apiClient.get = async (url: string, config: unknown) => {
      capturedUrl = url
      const cfg = config as { headers: Record<string, string> }
      capturedHeaders = cfg?.headers || {}
      return {
        status: 200,
        data: {
          id: 'test-inc-id',
          tenantId: BETAPAY_TENANT_ID,
          fingerprintHash: 'hash-beta',
          serviceName: 'wallet-service',
          status: 'OPEN',
          occurrenceCount: 12,
        },
      }
    }

    try {
      const result = await fetchIncident(BETAPAY_TENANT_ID, 'test-inc-id', 'beta-key')
      assert.equal(capturedUrl, '/incidents/test-inc-id')
      assert.equal(capturedHeaders['X-Tenant-ID'], BETAPAY_TENANT_ID)
      assert.equal(capturedHeaders['X-API-Key'], 'beta-key')
      assert.equal(result.serviceName, 'wallet-service')
    } finally {
      apiClient.get = originalGet
    }
  })
})

describe('Task 22: Credential Security & Invariants', () => {
  it('does not leak API keys when DEV is not active', () => {
    _setDevEnvForTesting(null)
    assert.equal(getDevComposeKey(ALPHAFIN_TENANT_ID), '')
    assert.equal(getDevComposeKey(BETAPAY_TENANT_ID), '')
  })
})
