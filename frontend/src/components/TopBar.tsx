import { RefreshCw, Signal, Wifi, WifiOff } from 'lucide-react'
import { KpiCards } from './KpiCards'
import { TenantSwitcher } from './TenantSwitcher'
import { TenantApiKeyBar } from './TenantApiKeyBar'
import type { Tenant, Telemetry } from '../types'

interface TopBarProps {
  tenants: Tenant[]
  tenantId: string
  onTenantChange: (tenantId: string) => void
  apiKey: string
  onApiKeyChange: (apiKey: string) => void
  telemetry: Telemetry | null
  loadingTelemetry: boolean
  connection: 'connecting' | 'live' | 'offline'
  onRefresh: () => void
  refreshing: boolean
}

export function TopBar({
  tenants,
  tenantId,
  onTenantChange,
  apiKey,
  onApiKeyChange,
  telemetry,
  loadingTelemetry,
  connection,
  onRefresh,
  refreshing,
}: TopBarProps) {
  const currentTenant = tenants.find((t) => t.id === tenantId) || tenants[0]

  return (
    <header className="topbar">
      <div className="topbar-primary">
        <div className="brand-lockup">
          <div className="brand-mark" aria-hidden="true">
            <Signal size={17} strokeWidth={2.2} />
          </div>
          <div className="brand-name">
            Aegis<span>.</span>RCA
          </div>
          <div className="brand-divider" aria-hidden="true" />
          <span className="workspace-label">Multi-Tenant Incident Intelligence</span>
        </div>

        <div className="topbar-right">
          <div className={`connection-pill ${connection}`} role="status" aria-live="polite">
            <span className="connection-icon" aria-hidden="true">
              {connection === 'live' ? (
                <Wifi size={13} />
              ) : connection === 'connecting' ? (
                <RefreshCw size={13} className="spin" />
              ) : (
                <WifiOff size={13} />
              )}
            </span>
            <span className="connection-label">
              {connection === 'live'
                ? 'Stream Live'
                : connection === 'connecting'
                ? 'Connecting…'
                : 'Stream Offline'}
            </span>
          </div>

          <button
            type="button"
            onClick={onRefresh}
            disabled={refreshing}
            className="refresh-button"
            title="Reload telemetry and incidents"
            aria-label="Refresh dashboard data"
          >
            <RefreshCw size={13} className={refreshing ? 'spin' : ''} />
            <span>Sync</span>
          </button>
        </div>
      </div>

      <div className="page-heading">
        <div className="page-heading-left">
          <div className="breadcrumb">
            <span>Tenant Workspace</span>
            <span className="crumb-slash">/</span>
            <span>{currentTenant?.name || tenantId}</span>
          </div>
          <div className="title-row">
            <h1>Incident Dashboard</h1>
            <span className="environment-badge">
              <span aria-hidden="true" /> Live Telemetry
            </span>
          </div>
          <p className="page-subtitle">
            Tenant-isolated real-time telemetry, log deduplication, and correlated incident signals.
          </p>
        </div>

        <div className="heading-actions">
          <div className="tenant-control-group">
            <label className="control-label">Tenant</label>
            <TenantSwitcher tenants={tenants} value={tenantId} onChange={onTenantChange} />
          </div>

          <div className="auth-control-group">
            <label className="control-label">API Key (X-API-Key)</label>
            <TenantApiKeyBar
              tenantId={tenantId}
              tenantName={currentTenant?.name || 'Tenant'}
              apiKey={apiKey}
              onApiKeyChange={onApiKeyChange}
            />
          </div>
        </div>
      </div>

      <KpiCards telemetry={telemetry} loading={loadingTelemetry} />
    </header>
  )
}
