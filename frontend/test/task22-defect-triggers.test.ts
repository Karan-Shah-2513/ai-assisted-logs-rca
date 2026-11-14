import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  AxiosError,
  AxiosHeaders,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { apiClient, triggerTenantDefect } from '../src/api.ts'
import { ALPHAFIN_TENANT_ID, BETAPAY_TENANT_ID } from '../src/auth.ts'

function createMockResponse<T>(
  status: number,
  data: T,
  statusText = 'OK',
  headersRecord?: Record<string, string>,
): AxiosResponse<T> {
  const headers = new AxiosHeaders(headersRecord)
  const config: InternalAxiosRequestConfig = { headers }
  return {
    data,
    status,
    statusText,
    headers,
    config,
  }
}

function createMockAxiosError(
  message: string,
  options?: {
    code?: string
    status?: number
    statusText?: string
    data?: unknown
    headersRecord?: Record<string, string>
  },
): AxiosError {
  const headers = new AxiosHeaders(options?.headersRecord)
  const config: InternalAxiosRequestConfig = { headers }
  let response: AxiosResponse | undefined

  if (options?.status !== undefined) {
    response = {
      data: options.data ?? {},
      status: options.status,
      statusText: options.statusText ?? '',
      headers,
      config,
    }
  }

  return new AxiosError(
    message,
    options?.code,
    config,
    undefined,
    response,
  )
}

describe('Task 22: Tenant Defect Trigger & Exception Contract (Review Findings)', () => {
  it('reports ECONNREFUSED network failure explicitly with useful message and zero shipped count', async () => {
    const originalPost = apiClient.post
    apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
      throw createMockAxiosError('connect ECONNREFUSED 127.0.0.1:8081', {
        code: 'ECONNREFUSED',
      })
    }

    try {
      const result = await triggerTenantDefect(
        'alphafin-order-checkout',
        1,
        ALPHAFIN_TENANT_ID,
        'dev-key',
      )

      assert.equal(result.success, false)
      assert.equal(result.attemptedCount, 1)
      assert.equal(result.shippedCount, 0)
      assert.equal(result.failedCount, 1)
      assert.equal(result.serviceName, 'order-service')
      assert.ok(result.message.includes('ECONNREFUSED'))
      assert.ok(result.message.includes('order-service unreachable'))
    } finally {
      apiClient.post = originalPost
    }
  })

  it('reports missing response / network timeout failure explicitly when no response is received', async () => {
    const originalPost = apiClient.post
    apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
      throw createMockAxiosError('timeout of 8000ms exceeded', {
        code: 'ECONNABORTED',
      })
    }

    try {
      const result = await triggerTenantDefect(
        'betapay-auth-token',
        2,
        BETAPAY_TENANT_ID,
        'dev-key',
      )

      assert.equal(result.success, false)
      assert.equal(result.attemptedCount, 2)
      assert.equal(result.shippedCount, 0)
      assert.equal(result.failedCount, 2)
      assert.equal(result.serviceName, 'auth-service')
      assert.ok(result.message.toLowerCase().includes('timeout'))
    } finally {
      apiClient.post = originalPost
    }
  })

  it('reports unexpected 200 OK as not triggered and marks operation as failure', async () => {
    const originalPost = apiClient.post
    apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
      return createMockResponse(200, {
        message: 'Order completed successfully without error',
      }) as unknown as R
    }

    try {
      const result = await triggerTenantDefect(
        'alphafin-order-checkout',
        1,
        ALPHAFIN_TENANT_ID,
        'dev-key',
      )

      assert.equal(result.success, false)
      assert.equal(result.attemptedCount, 1)
      assert.equal(result.shippedCount, 0)
      assert.equal(result.failedCount, 1)
      assert.equal(result.serviceName, 'order-service')
      assert.ok(result.message.includes('200'))
      assert.ok(result.message.includes('expected HTTP 500'))
    } finally {
      apiClient.post = originalPost
    }
  })

  it('reports unexpected 404 Not Found explicitly as failure with useful message', async () => {
    const originalPost = apiClient.post
    apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
      throw createMockAxiosError('Request failed with status code 404', {
        status: 404,
        statusText: 'Not Found',
        data: { message: 'Defect endpoint not found' },
      })
    }

    try {
      const result = await triggerTenantDefect(
        'alphafin-ledger-transfer',
        1,
        ALPHAFIN_TENANT_ID,
        'dev-key',
      )

      assert.equal(result.success, false)
      assert.equal(result.attemptedCount, 1)
      assert.equal(result.shippedCount, 0)
      assert.equal(result.failedCount, 1)
      assert.equal(result.serviceName, 'ledger-service')
      assert.ok(result.message.includes('404'))
      assert.ok(result.message.includes('expected HTTP 500'))
    } finally {
      apiClient.post = originalPost
    }
  })

  it('reports unexpected 502 Bad Gateway explicitly as failure with useful message', async () => {
    const originalPost = apiClient.post
    apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
      throw createMockAxiosError('Request failed with status code 502', {
        status: 502,
        statusText: 'Bad Gateway',
        data: { message: 'Gateway error' },
      })
    }

    try {
      const result = await triggerTenantDefect(
        'betapay-wallet-withdraw',
        1,
        BETAPAY_TENANT_ID,
        'dev-key',
      )

      assert.equal(result.success, false)
      assert.equal(result.attemptedCount, 1)
      assert.equal(result.shippedCount, 0)
      assert.equal(result.failedCount, 1)
      assert.equal(result.serviceName, 'wallet-service')
      assert.ok(result.message.includes('502'))
      assert.ok(result.message.includes('expected HTTP 500'))
    } finally {
      apiClient.post = originalPost
    }
  })

  it('handles mixed burst partial failure by reporting failure overall with precise counts', async () => {
    let callIndex = 0
    const originalPost = apiClient.post
    apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
      callIndex++
      if (callIndex === 2) {
        // Second call fails with 502 Bad Gateway
        throw createMockAxiosError('Request failed with status code 502', {
          status: 502,
          statusText: 'Bad Gateway',
        })
      }
      // Calls 1 and 3 succeed with expected 500 unhandled exception
      throw createMockAxiosError('Request failed with status code 500', {
        status: 500,
        statusText: 'Internal Server Error',
        data: {
          error: 'IndexOutOfBoundsException',
          correlationId: `corr-mixed-${callIndex}`,
        },
        headersRecord: { 'x-correlation-id': `corr-mixed-${callIndex}` },
      })
    }

    try {
      const result = await triggerTenantDefect(
        'alphafin-order-bulk',
        3,
        ALPHAFIN_TENANT_ID,
        'dev-key',
      )

      assert.equal(callIndex, 3)
      assert.equal(result.success, false) // Contractual rule: partial burst must NOT be marked overall success
      assert.equal(result.attemptedCount, 3)
      assert.equal(result.shippedCount, 2)
      assert.equal(result.failedCount, 1)
      assert.equal(result.serviceName, 'order-service')
      assert.ok(result.message.includes('Partial'))
      assert.ok(result.message.includes('2 of 3 succeeded'))
      assert.ok(result.message.includes('1 failed'))
      assert.ok(result.details?.includes('502'))
    } finally {
      apiClient.post = originalPost
    }
  })

  it('handles expected 500 case without claiming Airca delivery unless synchronously proven', async () => {
    let callIndex = 0
    const originalPost = apiClient.post
    apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
      callIndex++
      throw createMockAxiosError('Request failed with status code 500', {
        status: 500,
        statusText: 'Internal Server Error',
        data: {
          timestamp: '2026-11-10T12:00:00Z',
          status: 500,
          error: 'Internal Server Error',
          message:
            'An unexpected internal error occurred. Please contact support referencing correlationId: corr-reconcile-unbal-1',
          correlationId: 'corr-reconcile-unbal-1',
        },
        headersRecord: { 'x-correlation-id': 'corr-reconcile-unbal-1' },
      })
    }

    try {
      const result = await triggerTenantDefect(
        'alphafin-ledger-reconcile',
        4,
        ALPHAFIN_TENANT_ID,
        'dev-key',
      )

      assert.equal(callIndex, 4)
      assert.equal(result.success, true)
      assert.equal(result.attemptedCount, 4)
      assert.equal(result.shippedCount, 4)
      assert.equal(result.failedCount, 0)
      assert.equal(result.serviceName, 'ledger-service')
      assert.ok(
        result.message.includes('exception response observed and ingestion attempted'),
      )
      // Must not falsely claim confirmed delivery to central Airca
      assert.equal(result.message.includes('shipped to Airca'), false)
      assert.ok(result.details?.includes('corr-reconcile-unbal-1'))
    } finally {
      apiClient.post = originalPost
    }
  })

  it('handles auth failures (401 Unauthorized and 403 Forbidden) with actionable credentials guidance', async () => {
    const originalPost = apiClient.post
    try {
      // Test 401 Unauthorized
      apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
        throw createMockAxiosError('Request failed with status code 401', {
          status: 401,
          statusText: 'Unauthorized',
          data: { message: 'Invalid API Key' },
        })
      }

      const result401 = await triggerTenantDefect(
        'betapay-auth-mfa',
        1,
        BETAPAY_TENANT_ID,
        'invalid-api-key',
      )

      assert.equal(result401.success, false)
      assert.equal(result401.attemptedCount, 1)
      assert.equal(result401.shippedCount, 0)
      assert.equal(result401.failedCount, 1)
      assert.equal(result401.serviceName, 'auth-service')
      assert.ok(result401.message.includes('Authentication error (401)'))
      assert.ok(result401.message.includes('check X-API-Key credentials'))

      // Test 403 Forbidden
      apiClient.post = async <T = unknown, R = AxiosResponse<T>>(): Promise<R> => {
        throw createMockAxiosError('Request failed with status code 403', {
          status: 403,
          statusText: 'Forbidden',
          data: { message: 'Tenant mismatch' },
        })
      }

      const result403 = await triggerTenantDefect(
        'betapay-wallet-fx-quote',
        1,
        BETAPAY_TENANT_ID,
        'wrong-tenant-key',
      )

      assert.equal(result403.success, false)
      assert.equal(result403.attemptedCount, 1)
      assert.equal(result403.shippedCount, 0)
      assert.equal(result403.failedCount, 1)
      assert.equal(result403.serviceName, 'wallet-service')
      assert.ok(result403.message.includes('Authentication error (403)'))
      assert.ok(result403.message.includes('check X-API-Key credentials'))
    } finally {
      apiClient.post = originalPost
    }
  })
})
