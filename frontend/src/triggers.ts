import { ALPHAFIN_TENANT_ID, BETAPAY_TENANT_ID } from './auth.ts'
import type { TriggerOption } from './types.ts'

export const CHAOS_SCENARIOS = ['PAYMENT_DB_DEADLOCK', 'REDIS_OOM', 'AUTH_NPE'] as const
export type ChaosScenario = (typeof CHAOS_SCENARIOS)[number]

export const ALL_TRIGGER_OPTIONS: TriggerOption[] = [
  // Central Chaos Outage Simulation Scenarios
  {
    id: 'chaos-payment-db-deadlock',
    name: 'Payment DB Deadlock & Connection Pool Saturation',
    category: 'CHAOS_SIMULATION',
    scenario: 'PAYMENT_DB_DEADLOCK',
    serviceName: 'payment-service',
    description:
      'Simulates batch refund deadlock leading to java.sql.SQLException connection pool exhaustion.',
    tenantScope: ALPHAFIN_TENANT_ID,
    defaultCount: 500,
    maxCount: 2000,
  },
  {
    id: 'chaos-redis-oom',
    name: 'Redis Cache Out-of-Memory (noeviction)',
    category: 'CHAOS_SIMULATION',
    scenario: 'REDIS_OOM',
    serviceName: 'cache-service',
    description:
      'Simulates cache maxmemory limit reached throwing RedisConnectionFailureException: OOM command not allowed.',
    tenantScope: 'ALL',
    defaultCount: 250,
    maxCount: 2000,
  },
  {
    id: 'chaos-auth-npe',
    name: 'Auth Session NullPointerException',
    category: 'CHAOS_SIMULATION',
    scenario: 'AUTH_NPE',
    serviceName: 'auth-service',
    description:
      'Simulates token refresh on missing user session throwing java.lang.NullPointerException.',
    tenantScope: BETAPAY_TENANT_ID,
    defaultCount: 200,
    maxCount: 2000,
  },

  // AlphaFin Direct Tenant Microservice Defect Endpoints
  {
    id: 'alphafin-order-checkout',
    name: 'AlphaFin Order Service: Checkout NullPointerException',
    category: 'TENANT_DEFECT',
    serviceName: 'order-service',
    description:
      'Deliberately executes unguarded discountCode.toUpperCase() on null discount code, throwing real NullPointerException.',
    tenantScope: ALPHAFIN_TENANT_ID,
    endpoint: '/api/v1/orders/checkout',
    defectType: 'java.lang.NullPointerException',
    defaultCount: 5,
    maxCount: 20,
  },
  {
    id: 'alphafin-order-bulk',
    name: 'AlphaFin Order Service: Bulk Order Off-by-One Loop',
    category: 'TENANT_DEFECT',
    serviceName: 'order-service',
    description:
      'Deliberately runs off-by-one loop (i <= items.size()) on bulk order > 5 items, throwing real IndexOutOfBoundsException.',
    tenantScope: ALPHAFIN_TENANT_ID,
    endpoint: '/api/v1/orders/bulk',
    defectType: 'java.lang.IndexOutOfBoundsException',
    defaultCount: 5,
    maxCount: 20,
  },
  {
    id: 'alphafin-ledger-transfer',
    name: 'AlphaFin Ledger Service: Transfer Division by Zero',
    category: 'TENANT_DEFECT',
    serviceName: 'ledger-service',
    description:
      'Deliberately divides 10000L by 0 when exchangeRateSpread is 0, throwing real ArithmeticException: / by zero.',
    tenantScope: ALPHAFIN_TENANT_ID,
    endpoint: '/api/v1/ledger/transfer',
    defectType: 'java.lang.ArithmeticException',
    defaultCount: 5,
    maxCount: 20,
  },
  {
    id: 'alphafin-ledger-reconcile',
    name: 'AlphaFin Ledger Service: Reconcile Partition Lock Contention',
    category: 'TENANT_DEFECT',
    serviceName: 'ledger-service',
    description:
      'Deliberately checks unbalanced debit and credit totals, throwing real IllegalStateException lock contention.',
    tenantScope: ALPHAFIN_TENANT_ID,
    endpoint: '/api/v1/ledger/reconcile',
    defectType: 'java.lang.IllegalStateException',
    defaultCount: 5,
    maxCount: 20,
  },

  // BetaPay Direct Tenant Microservice Defect Endpoints
  {
    id: 'betapay-auth-token',
    name: 'BetaPay Auth Service: Token Parse / Expired Validation Defect',
    category: 'TENANT_DEFECT',
    serviceName: 'auth-service',
    description:
      'Deliberately parses malformed or expired JWT claim data, throwing real IllegalArgumentException.',
    tenantScope: BETAPAY_TENANT_ID,
    endpoint: '/api/v1/auth/token',
    defectType: 'java.lang.IllegalArgumentException',
    defaultCount: 5,
    maxCount: 20,
  },
  {
    id: 'betapay-auth-mfa',
    name: 'BetaPay Auth Service: MFA Missing Fingerprint Dereference',
    category: 'TENANT_DEFECT',
    serviceName: 'auth-service',
    description:
      'Deliberately executes unguarded fingerprint.trim() on missing deviceMetadata, throwing real NullPointerException.',
    tenantScope: BETAPAY_TENANT_ID,
    endpoint: '/api/v1/auth/mfa',
    defectType: 'java.lang.NullPointerException',
    defaultCount: 5,
    maxCount: 20,
  },
  {
    id: 'betapay-wallet-withdraw',
    name: 'BetaPay Wallet Service: Withdrawal Liquidity Lock Timeout',
    category: 'TENANT_DEFECT',
    serviceName: 'wallet-service',
    description:
      'Deliberately requests amount exceeding liquidity threshold, throwing real IllegalStateException liquidity lock timeout.',
    tenantScope: BETAPAY_TENANT_ID,
    endpoint: '/api/v1/wallet/withdraw',
    defectType: 'java.lang.IllegalStateException',
    defaultCount: 5,
    maxCount: 20,
  },
  {
    id: 'betapay-wallet-fx-quote',
    name: 'BetaPay Wallet Service: FX Quote Malformed Rate Parse',
    category: 'TENANT_DEFECT',
    serviceName: 'wallet-service',
    description:
      'Deliberately triggers unvalidated Double.parseDouble on non-numeric currency pair, throwing real NumberFormatException.',
    tenantScope: BETAPAY_TENANT_ID,
    endpoint: '/api/v1/wallet/fx-quote',
    defectType: 'java.lang.NumberFormatException',
    defaultCount: 5,
    maxCount: 20,
  },
]
