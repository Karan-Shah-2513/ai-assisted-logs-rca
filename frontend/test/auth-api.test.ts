import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildTenantHeaders } from '../src/api.ts'
import {
  ALPHAFIN_TENANT_ID,
  BETAPAY_TENANT_ID,
  getDevComposeKey,
  getTenantApiKey,
  setTenantApiKey,
  _setDevEnvForTesting,
} from '../src/auth.ts'
import { tenants } from '../src/data.ts'

describe('Tenant Authentication & API Headers Contract (Task 21)', () => {
  it('correctly maps known tenant IDs to AlphaFin and BetaPay', () => {
    assert.equal(ALPHAFIN_TENANT_ID, '11111111-1111-1111-1111-111111111111')
    assert.equal(BETAPAY_TENANT_ID, '22222222-2222-2222-2222-222222222222')

    const alpha = tenants.find((t) => t.id === ALPHAFIN_TENANT_ID)
    const beta = tenants.find((t) => t.id === BETAPAY_TENANT_ID)

    assert.ok(alpha, 'AlphaFin tenant exists in seed tenant list')
    assert.ok(beta, 'BetaPay tenant exists in seed tenant list')
  })

  it('buildTenantHeaders produces X-Tenant-ID and X-API-Key headers for REST requests', () => {
    const headersWithoutKey = buildTenantHeaders(ALPHAFIN_TENANT_ID)
    assert.deepEqual(headersWithoutKey, {
      'X-Tenant-ID': ALPHAFIN_TENANT_ID,
    })

    const headersWithKey = buildTenantHeaders(ALPHAFIN_TENANT_ID, 'custom-alpha-key')
    assert.deepEqual(headersWithKey, {
      'X-Tenant-ID': ALPHAFIN_TENANT_ID,
      'X-API-Key': 'custom-alpha-key',
    })

    const headersTrimmed = buildTenantHeaders(BETAPAY_TENANT_ID, '  custom-beta-key  ')
    assert.deepEqual(headersTrimmed, {
      'X-Tenant-ID': BETAPAY_TENANT_ID,
      'X-API-Key': 'custom-beta-key',
    })
  })

  it('does not leak dev compose keys when DEV mode is not active', () => {
    _setDevEnvForTesting(null)
    assert.equal(getDevComposeKey(ALPHAFIN_TENANT_ID), '')
    assert.equal(getDevComposeKey(BETAPAY_TENANT_ID), '')
    assert.equal(getDevComposeKey('unknown-uuid'), '')
  })

  it('provides dev keys for quick-fill assistance only when DEV mode is active with configured env', () => {
    _setDevEnvForTesting({
      DEV: true,
      VITE_AIRCA_API_KEY_ALPHAFIN: 'alphafin-mock-dev-key',
      VITE_AIRCA_API_KEY_BETAPAY: 'betapay-mock-dev-key',
    })

    assert.equal(getDevComposeKey(ALPHAFIN_TENANT_ID), 'alphafin-mock-dev-key')
    assert.equal(getDevComposeKey(BETAPAY_TENANT_ID), 'betapay-mock-dev-key')
    assert.equal(getDevComposeKey('unknown-uuid'), '')

    // Clean up mock
    _setDevEnvForTesting(null)
  })

  it('manages session-scoped API keys via sessionStorage', () => {
    _setDevEnvForTesting(null)
    // Setup mock sessionStorage
    const storage = new Map<string, string>()
    const mockSessionStorage = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, val: string) => storage.set(key, val),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    }
    // @ts-expect-error mock assignment
    globalThis.sessionStorage = mockSessionStorage

    // Initially no session key set
    const keyBefore = getTenantApiKey('test-tenant-1')
    assert.equal(keyBefore, '')

    // Set custom session key
    setTenantApiKey('test-tenant-1', 'custom-user-key-123')
    assert.equal(getTenantApiKey('test-tenant-1'), 'custom-user-key-123')

    // Overwrite with new key
    setTenantApiKey('test-tenant-1', 'updated-key-456')
    assert.equal(getTenantApiKey('test-tenant-1'), 'updated-key-456')

    // Setting empty key clears it
    setTenantApiKey('test-tenant-1', '')
    assert.equal(getTenantApiKey('test-tenant-1'), '')
  })
})
