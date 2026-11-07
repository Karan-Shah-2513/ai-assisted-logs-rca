import { Building2, ChevronDown } from 'lucide-react'
import type { Tenant } from '../types'

interface TenantSwitcherProps {
  tenants: Tenant[]
  value: string
  onChange: (tenantId: string) => void
}

export function TenantSwitcher({ tenants, value, onChange }: TenantSwitcherProps) {
  return (
    <label className="tenant-switcher">
      <Building2 size={15} aria-hidden="true" />
      <span className="sr-only">Select tenant</span>
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        {tenants.map((tenant) => (
          <option value={tenant.id} key={tenant.id}>{tenant.name}</option>
        ))}
      </select>
      <ChevronDown size={14} className="tenant-chevron" aria-hidden="true" />
    </label>
  )
}
