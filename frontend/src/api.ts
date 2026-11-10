import axios from 'axios'
import type {
  Incident,
  Telemetry,
  Tenant,
  ResolveIncidentRequest,
  ChaosSimulationResponse,
} from './types'

export const apiClient = axios.create({ baseURL: '/api/v1', timeout: 8_000 })
const client = apiClient

/**
 * Builds HTTP headers required by Airca multi-tenant endpoints (Task 15).
 * Both X-Tenant-ID and X-API-Key are passed when available.
 */
export function buildTenantHeaders(tenantId: string, apiKey?: string): Record<string, string> {
  const headers: Record<string, string> = {
    'X-Tenant-ID': tenantId,
  }
  if (apiKey && apiKey.trim()) {
    headers['X-API-Key'] = apiKey.trim()
  }
  return headers
}

export async function fetchIncidents(tenantId: string, apiKey?: string): Promise<Incident[]> {
  const { data } = await client.get<Incident[]>('/incidents', {
    headers: buildTenantHeaders(tenantId, apiKey),
  })
  return data
}

export async function fetchIncident(
  tenantId: string,
  incidentId: string,
  apiKey?: string,
): Promise<Incident> {
  const { data } = await client.get<Incident>(`/incidents/${encodeURIComponent(incidentId)}`, {
    headers: buildTenantHeaders(tenantId, apiKey),
  })
  return data
}

export async function fetchTelemetry(tenantId: string, apiKey?: string): Promise<Telemetry> {
  const { data } = await client.get<Telemetry>('/telemetry', {
    headers: buildTenantHeaders(tenantId, apiKey),
  })
  return data
}

export async function fetchTenants(tenantId?: string, apiKey?: string): Promise<Tenant[]> {
  const headers = tenantId ? buildTenantHeaders(tenantId, apiKey) : undefined
  const { data } = await client.get<Tenant[]>('/tenants', { headers })
  return data
}

/**
 * Dispatches chaos simulation outage burst to POST /api/v1/simulate-outage.
 * Validates contract request shape: { tenantId, scenario, logCount }
 */
export async function simulateOutage(
  tenantId: string,
  scenario: string,
  logCount: number,
  apiKey?: string,
): Promise<ChaosSimulationResponse> {
  const { data } = await client.post<ChaosSimulationResponse>(
    '/simulate-outage',
    { tenantId, scenario, logCount },
    { headers: buildTenantHeaders(tenantId, apiKey) },
  )
  return data
}

/**
 * Submits verified incident resolution to POST /api/v1/incidents/{id}/resolve.
 * Contract requires { verifiedRootCause, verifiedFixCommandOrPR, resolvedBy } nonblank.
 */
export async function resolveIncident(
  tenantId: string,
  incidentId: string,
  payload: ResolveIncidentRequest,
  apiKey?: string,
): Promise<Incident> {
  if (!payload.verifiedRootCause || !payload.verifiedRootCause.trim()) {
    throw new Error('verifiedRootCause must not be blank')
  }
  if (!payload.verifiedFixCommandOrPR || !payload.verifiedFixCommandOrPR.trim()) {
    throw new Error('verifiedFixCommandOrPR must not be blank')
  }
  if (!payload.resolvedBy || !payload.resolvedBy.trim()) {
    throw new Error('resolvedBy must not be blank')
  }

  const { data } = await client.post<Incident>(
    `/incidents/${encodeURIComponent(incidentId)}/resolve`,
    {
      verifiedRootCause: payload.verifiedRootCause.trim(),
      verifiedFixCommandOrPR: payload.verifiedFixCommandOrPR.trim(),
      resolvedBy: payload.resolvedBy.trim(),
    },
    { headers: buildTenantHeaders(tenantId, apiKey) },
  )
  return data
}

export interface DefectTriggerResult {
  success: boolean
  message: string
  serviceName: string
  details?: string
  attemptedCount: number
  shippedCount: number
  failedCount: number
  attempted: number
  successful: number
  failed: number
}

interface ParsedHttpError {
  status?: number
  statusText?: string
  code?: string
  message: string
  correlationId?: string
}

function parseHttpError(err: unknown): ParsedHttpError {
  if (typeof err !== 'object' || err === null) {
    return { message: String(err) }
  }

  const record = err as Record<string, unknown>
  const message = typeof record.message === 'string' ? record.message : 'Unknown error'
  const code = typeof record.code === 'string' ? record.code : undefined

  let status: number | undefined
  let statusText: string | undefined
  let correlationId: string | undefined

  if (typeof record.response === 'object' && record.response !== null) {
    const resp = record.response as Record<string, unknown>
    if (typeof resp.status === 'number') {
      status = resp.status
    }
    if (typeof resp.statusText === 'string') {
      statusText = resp.statusText
    }
    if (typeof resp.headers === 'object' && resp.headers !== null) {
      const hdrs = resp.headers as Record<string, unknown>
      if ('get' in hdrs && typeof hdrs.get === 'function') {
        const val = (hdrs as { get: (name: string) => unknown }).get('x-correlation-id')
        if (typeof val === 'string') {
          correlationId = val
        }
      }
      if (!correlationId) {
        const cid = hdrs['x-correlation-id'] || hdrs['X-Correlation-ID']
        if (typeof cid === 'string') {
          correlationId = cid
        }
      }
    }
    if (!correlationId && typeof resp.data === 'object' && resp.data !== null) {
      const data = resp.data as Record<string, unknown>
      if (typeof data.correlationId === 'string') {
        correlationId = data.correlationId
      }
    }
  }

  return { status, statusText, code, message, correlationId }
}

/**
 * Triggers intentional defect on one of the four tenant microservices.
 * Microservices intentionally respond with an unhandled exception (HTTP 500 per
 * TenantGlobalExceptionHandler contract), which triggers synchronous ingestion attempt to Airca.
 * Successful 2xx responses are reported as not triggered.
 */
export async function triggerTenantDefect(
  triggerId: string,
  burstCount: number,
  tenantId: string,
  apiKey?: string,
): Promise<DefectTriggerResult> {
  const boundedCount = Math.min(Math.max(1, burstCount), 20)
  const headers = buildTenantHeaders(tenantId, apiKey)

  let endpoint = ''
  let payload: unknown = {}
  let serviceName = ''

  switch (triggerId) {
    case 'alphafin-order-checkout':
      endpoint = '/orders/checkout'
      payload = {} // discountCode is null -> NullPointerException
      serviceName = 'order-service'
      break
    case 'alphafin-order-bulk':
      endpoint = '/orders/bulk'
      payload = {
        items: [1, 2, 3, 4, 5, 6].map((i) => ({ orderId: `bulk-item-${i}` })),
      } // >5 items -> IndexOutOfBoundsException
      serviceName = 'order-service'
      break
    case 'alphafin-ledger-transfer':
      endpoint = '/ledger/transfer'
      payload = { exchangeRateSpread: 0 } // spread = 0 -> ArithmeticException: / by zero
      serviceName = 'ledger-service'
      break
    case 'alphafin-ledger-reconcile':
      endpoint = '/ledger/reconcile'
      payload = { totalDebits: 2000, totalCredits: 1000 } // unbalanced -> IllegalStateException lock contention
      serviceName = 'ledger-service'
      break
    case 'betapay-auth-token':
      endpoint = '/auth/token'
      payload = { claims: ['eyJhbGciOiJIUzI1NiJ9.invalid.sig'] } // malformed JWT claim -> IllegalArgumentException
      serviceName = 'auth-service'
      break
    case 'betapay-auth-mfa':
      endpoint = '/auth/mfa'
      payload = { userId: 'usr-betapay-demo' } // missing fingerprint -> NullPointerException
      serviceName = 'auth-service'
      break
    case 'betapay-wallet-withdraw':
      endpoint = '/wallet/withdraw'
      payload = { amount: 25000, threshold: 10000 } // amount > threshold -> IllegalStateException liquidity timeout
      serviceName = 'wallet-service'
      break
    case 'betapay-wallet-fx-quote':
      endpoint = '/wallet/fx-quote'
      payload = { pair: 'MALFORMED-RATE' } // unparseable pair -> NumberFormatException
      serviceName = 'wallet-service'
      break
    default:
      throw new Error(`Unknown tenant defect trigger ID: ${triggerId}`)
  }

  let attemptedCount = 0
  let shippedCount = 0
  let failedCount = 0
  const failureReasons: string[] = []
  const correlationIds: string[] = []

  for (let i = 0; i < boundedCount; i++) {
    attemptedCount++
    try {
      const response = await client.post(endpoint, payload, { headers })
      // Successful 2xx response means the exception did not occur.
      // Under TenantGlobalExceptionHandler contract, intentional defects MUST produce HTTP 500.
      failedCount++
      const respStatus = typeof response?.status === 'number' ? response.status : 200
      failureReasons.push(
        `Unexpected HTTP ${respStatus}: endpoint succeeded without throwing unhandled exception`,
      )
    } catch (err: unknown) {
      const { status, statusText, code, message, correlationId } = parseHttpError(err)
      if (correlationId) {
        correlationIds.push(correlationId)
      }

      if (status === 500) {
        // Expected defect behavior per TenantGlobalExceptionHandler contract
        shippedCount++
        continue
      }

      failedCount++
      if (status === 401 || status === 403) {
        failureReasons.push(`Authentication error (${status}): check X-API-Key credentials`)
      } else if (status !== undefined) {
        const textSuffix = statusText ? ` ${statusText}` : ''
        failureReasons.push(
          `Unexpected HTTP status ${status}${textSuffix} (expected HTTP 500 unhandled exception)`,
        )
      } else if (code === 'ECONNREFUSED' || message.includes('ECONNREFUSED')) {
        failureReasons.push(
          `Connection refused (ECONNREFUSED): ${serviceName} unreachable at ${endpoint}`,
        )
      } else if (
        code === 'ECONNABORTED' ||
        code === 'ETIMEDOUT' ||
        message.toLowerCase().includes('timeout')
      ) {
        failureReasons.push(
          `Request timeout: no response received from ${serviceName} within timeout limit at ${endpoint}`,
        )
      } else {
        failureReasons.push(
          `Network error (no response received): ${message || 'connection failed'}`,
        )
      }
    }
  }

  const isContractuallyJustified =
    attemptedCount > 0 && failedCount === 0 && shippedCount === attemptedCount

  if (isContractuallyJustified) {
    const corrSuffix =
      correlationIds.length > 0
        ? ` (correlation: ${Array.from(new Set(correlationIds)).join(', ')})`
        : ''
    return {
      success: true,
      serviceName,
      message: `Triggered ${shippedCount} defect call(s) to ${serviceName} (${endpoint}): expected HTTP 500 exception response observed and ingestion attempted.${corrSuffix}`,
      ...(correlationIds.length > 0
        ? { details: `Correlation IDs: ${Array.from(new Set(correlationIds)).join(', ')}` }
        : {}),
      attemptedCount,
      shippedCount,
      failedCount,
      attempted: attemptedCount,
      successful: shippedCount,
      failed: failedCount,
    }
  }

  const distinctReasons = Array.from(new Set(failureReasons))

  if (shippedCount > 0) {
    // Mixed burst partial failure: prefer all requested calls returned expected 500; report partial as failure
    const primaryReason = distinctReasons[0] || 'unexpected failure'
    return {
      success: false,
      serviceName,
      message: `Partial defect trigger failure on ${serviceName}: ${shippedCount} of ${attemptedCount} succeeded, ${failedCount} failed (${primaryReason}).`,
      details: failureReasons.join('; '),
      attemptedCount,
      shippedCount,
      failedCount,
      attempted: attemptedCount,
      successful: shippedCount,
      failed: failedCount,
    }
  }

  // 0 calls produced expected 500
  const primaryReason = distinctReasons[0] || 'Unknown failure'
  let failMessage = `Failed to trigger defect on ${serviceName}: ${primaryReason}`
  if (primaryReason.startsWith('Authentication error')) {
    failMessage = `${primaryReason} for ${serviceName}`
  } else if (primaryReason.startsWith('Unexpected HTTP 200')) {
    failMessage = `Defect not triggered on ${serviceName} (${endpoint}): received unexpected HTTP 200 (expected HTTP 500 unhandled exception)`
  }

  return {
    success: false,
    serviceName,
    message: failMessage,
    details: failureReasons.join('; '),
    attemptedCount,
    shippedCount,
    failedCount,
    attempted: attemptedCount,
    successful: shippedCount,
    failed: failedCount,
  }
}
