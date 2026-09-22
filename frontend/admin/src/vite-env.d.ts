/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Empty for same-origin. See `.env.example` — it is empty for a reason. */
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_DEV_API_TARGET?: string;
  readonly VITE_IDLE_TIMEOUT_MINUTES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
