import { useState, useEffect } from 'react'
import { Check, Eye, EyeOff, KeyRound, RotateCcw, Sparkles } from 'lucide-react'
import { getDevComposeKey } from '../auth'

interface TenantApiKeyBarProps {
  tenantId: string
  tenantName: string
  apiKey: string
  onApiKeyChange: (newKey: string) => void
}

export function TenantApiKeyBar({
  tenantId,
  tenantName,
  apiKey,
  onApiKeyChange,
}: TenantApiKeyBarProps) {
  const [inputValue, setInputValue] = useState(apiKey)
  const [showKey, setShowKey] = useState(false)
  const [isSaved, setIsSaved] = useState(false)

  // Keep input synchronized when tenant or parent apiKey changes
  useEffect(() => {
    setInputValue(apiKey)
  }, [apiKey, tenantId])

  const handleSave = (e?: React.FormEvent) => {
    if (e) e.preventDefault()
    onApiKeyChange(inputValue.trim())
    setIsSaved(true)
    setTimeout(() => setIsSaved(false), 2000)
  }

  const handleFillDevKey = () => {
    const devKey = getDevComposeKey(tenantId)
    if (devKey) {
      setInputValue(devKey)
      onApiKeyChange(devKey)
      setIsSaved(true)
      setTimeout(() => setIsSaved(false), 2000)
    }
  }

  const handleClear = () => {
    setInputValue('')
    onApiKeyChange('')
  }

  const isConfigured = Boolean(apiKey && apiKey.trim())
  const devKey = getDevComposeKey(tenantId)

  return (
    <div className="tenant-auth-widget" role="region" aria-label="Tenant API Key Configuration">
      <form onSubmit={handleSave} className="api-key-form">
        <div className="api-key-input-group">
          <span className="api-key-icon" aria-hidden="true">
            <KeyRound size={13} />
          </span>
          <label htmlFor={`api-key-${tenantId}`} className="sr-only">
            API Key for {tenantName}
          </label>
          <input
            id={`api-key-${tenantId}`}
            type={showKey ? 'text' : 'password'}
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            placeholder={`Enter X-API-Key for ${tenantName}...`}
            className="api-key-input"
            autoComplete="off"
            spellCheck="false"
          />
          <button
            type="button"
            onClick={() => setShowKey(!showKey)}
            className="api-key-toggle-btn"
            title={showKey ? 'Hide key' : 'Show key'}
            aria-label={showKey ? 'Hide API key' : 'Show API key'}
          >
            {showKey ? <EyeOff size={13} /> : <Eye size={13} />}
          </button>
        </div>

        <div className="api-key-actions">
          <button
            type="submit"
            className={`api-key-save-btn ${isSaved ? 'saved' : ''}`}
            disabled={inputValue === apiKey && isConfigured}
            title="Save key to active session"
          >
            {isSaved ? (
              <>
                <Check size={12} />
                <span>Saved</span>
              </>
            ) : (
              <span>Save</span>
            )}
          </button>

          {!isConfigured && devKey && (
            <button
              type="button"
              onClick={handleFillDevKey}
              className="dev-fill-btn"
              title={`Fill default compose key: ${devKey}`}
            >
              <Sparkles size={11} />
              <span>Use dev key</span>
            </button>
          )}

          {isConfigured && (
            <button
              type="button"
              onClick={handleClear}
              className="api-key-clear-btn"
              title="Clear key for this session"
              aria-label="Clear API key"
            >
              <RotateCcw size={11} />
            </button>
          )}
        </div>
      </form>

      <div className="api-key-status">
        {isConfigured ? (
          <span className="key-badge active" title="X-API-Key configured for REST and STOMP CONNECT">
            <span className="status-dot green" />
            Key configured
          </span>
        ) : (
          <span className="key-badge warning" title="X-API-Key required by backend endpoints">
            <span className="status-dot amber" />
            Key required
          </span>
        )}
      </div>
    </div>
  )
}
