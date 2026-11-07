import { Bell, ChevronDown, Command, LifeBuoy, Search, ShieldCheck, Signal } from 'lucide-react'
import { ChaosSimulator, type ChaosScenario } from './ChaosSimulator'
import { KpiCards } from './KpiCards'
import { TenantSwitcher } from './TenantSwitcher'
import type { Tenant, Telemetry } from '../types'

interface TopBarProps {
  tenants: Tenant[]
  tenantId: string
  onTenantChange: (tenantId: string) => void
  telemetry: Telemetry
  busy: boolean
  onSimulate: (scenario: ChaosScenario, logCount: number) => void
}

export function TopBar({ tenants, tenantId, onTenantChange, telemetry, busy, onSimulate }: TopBarProps) {
  return (
    <header className="topbar">
      <div className="topbar-primary">
        <div className="brand-lockup">
          <div className="brand-mark"><Signal size={17} strokeWidth={2.2} /></div>
          <div className="brand-name">signal<span>.</span></div>
          <div className="brand-divider" />
          <span className="workspace-label">Incident intelligence</span>
        </div>
        <div className="topbar-tools">
          <div className="command-search">
            <Search size={14} />
            <span>Search anything...</span>
            <kbd><Command size={10} /> K</kbd>
          </div>
          <div className="topbar-icon-button" title="Notifications"><Bell size={16} /><i /></div>
          <div className="user-avatar" aria-label="User account">JD</div>
          <ChevronDown size={13} className="user-chevron" />
        </div>
      </div>
      <div className="page-heading">
        <div>
          <div className="breadcrumb"><span>Workspace</span><span className="crumb-slash">/</span><span>Overview</span></div>
          <div className="title-row">
            <h1>Incident overview</h1>
            <span className="environment-badge"><span /> Production</span>
          </div>
          <p className="page-subtitle">A real-time view of your system health and incident response.</p>
        </div>
        <div className="heading-actions">
          <div className="secure-label"><ShieldCheck size={14} /> SOC 2 monitored</div>
          <button className="quiet-button" type="button"><LifeBuoy size={14} /> Help center</button>
          <TenantSwitcher tenants={tenants} value={tenantId} onChange={onTenantChange} />
        </div>
      </div>
      <KpiCards telemetry={telemetry} />
      <ChaosSimulator busy={busy} onTrigger={onSimulate} />
    </header>
  )
}
