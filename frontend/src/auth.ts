/**
 * Tenant API Key management module.
 * Provides session-scoped storage and dev-only fallback for tenant API keys.
 * Ensures production credentials must be entered via session-scoped UI and
 * are never embedded via VITE vars in production bundle.
 */

export const ALPHAFIN_TENANT_ID = '11111111-1111-1111-1111-111111111111'
export const BETAPAY_TENANT_ID = '22222222-2222-2222-2222-222222222222'

const SESSION_KEY_PREFIX = 'airca_api_key_'

/**
 * Returns dev compose key for a known tenant ID (for quick-fill in dev UI only).
 * Strictly guarded by import.meta.env.DEV so Vite dead-code eliminates it in production.
 */
export function getDevComposeKey(tenantId: string): string {
  if (Boolean(import.meta.env && import.meta.env.DEV)) {
    if (tenantId === ALPHAFIN_TENANT_ID) {
      return (import.meta.env.VITE_AIRCA_API_KEY_ALPHAFIN as string | undefined) || ''
    }
    if (tenantId === BETAPAY_TENANT_ID) {
      return (import.meta.env.VITE_AIRCA_API_KEY_BETAPAY as string | undefined) || ''
    }
  }
  return ''
}

/**
 * Resolves the active API key for a tenant:
 * 1. Checks session storage (user-configured key for the current session)
 * 2. Checks dev-only environment variables when running in development mode
 * 3. In production, credentials must be entered via session-scoped UI
 */
export function getTenantApiKey(tenantId: string): string {
  // 1. Session-scoped storage (primary, mandatory for production)
  try {
    const sessionKey = sessionStorage.getItem(`${SESSION_KEY_PREFIX}${tenantId}`)
    if (sessionKey && sessionKey.trim()) {
      return sessionKey.trim()
    }
  } catch {
    // sessionStorage might be restricted in some iframe / sandboxed environments
  }

  // 2. Dev-only fallback: strictly guarded by import.meta.env.DEV
  if (Boolean(import.meta.env && import.meta.env.DEV)) {
    if (tenantId === ALPHAFIN_TENANT_ID) {
      const envKey = import.meta.env.VITE_AIRCA_API_KEY_ALPHAFIN as string | undefined
      if (envKey && typeof envKey === 'string' && envKey.trim()) {
        return envKey.trim()
      }
    } else if (tenantId === BETAPAY_TENANT_ID) {
      const devKey = import.meta.env.VITE_AIRCA_API_KEY_BETAPAY as string | undefined
      if (devKey && typeof devKey === 'string' && devKey.trim()) {
        return devKey.trim()
      }
    }
  }

  return ''
}

/**
 * Test helper for simulating dev-only environment in Node test runner.
 * Tree-shaken out of production bundles as it is unreferenced by the app.
 */
export function _setDevEnvForTesting(mockEnv: Record<string, unknown> | null): void {
  if (mockEnv) {
    // @ts-expect-error test-only injection
    import.meta.env = mockEnv
  } else {
    Reflect.deleteProperty(import.meta, 'env')
  }
}

/**
 * Saves a tenant API key to session-scoped storage.
 */
export function setTenantApiKey(tenantId: string, apiKey: string): void {
  try {
    const cleanKey = apiKey.trim()
    if (cleanKey) {
      sessionStorage.setItem(`${SESSION_KEY_PREFIX}${tenantId}`, cleanKey)
    } else {
      sessionStorage.removeItem(`${SESSION_KEY_PREFIX}${tenantId}`)
    }
  } catch {
    // ignore
  }
}

/**
 * Clears session-scoped key for a tenant, restoring env fallback.
 */
export function clearTenantApiKey(tenantId: string): void {
  try {
    sessionStorage.removeItem(`${SESSION_KEY_PREFIX}${tenantId}`)
  } catch {
    // ignore
  }
}
