/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_AIRCA_API_KEY_ALPHAFIN?: string
  readonly VITE_API_KEY_ALPHAFIN?: string
  readonly VITE_AIRCA_API_KEY_BETAPAY?: string
  readonly VITE_API_KEY_BETAPAY?: string
  readonly VITE_AIRCA_API_KEY?: string
  readonly VITE_API_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
