import { createClient, type SupabaseClient } from '@supabase/supabase-js';

/** The parts of the Supabase client the app uses; tests substitute a fake. */
export type AuthClient = Pick<SupabaseClient, 'auth' | 'removeAllChannels'>;

export interface RuntimeConfig {
  /** Public (publishable) key. Never a secret or service-role key. */
  supabasePublishableKey: string;
}

/**
 * Loads `/config.json`, which the edge renders from the deployment's public key, so one image
 * runs with any generated key set. `vite dev` falls back to `VITE_SUPABASE_PUBLISHABLE_KEY`.
 */
export async function loadRuntimeConfig(fetchImpl: typeof fetch = fetch): Promise<RuntimeConfig> {
  try {
    const response = await fetchImpl('/config.json', { cache: 'no-store', credentials: 'omit' });
    if (response.ok) {
      const body: unknown = await response.json();
      const key = (body as Partial<RuntimeConfig> | null)?.supabasePublishableKey;
      if (typeof key === 'string' && key !== '') {
        return { supabasePublishableKey: key };
      }
    }
  } catch {
    // Fall through to the development fallback.
  }
  const devKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY as string | undefined;
  if (devKey) {
    return { supabasePublishableKey: devKey };
  }
  throw new Error('Runtime configuration is unavailable.');
}

/**
 * Supabase JS client for Auth (and later Realtime). It talks to the same origin, because the
 * edge proxies `/auth/v1/` and the CSP allows only `connect-src 'self'`.
 *
 * The session lives in tab-scoped `sessionStorage`, never `localStorage`: closing the tab ends
 * it, and a second tab signs in independently. Tokens refresh automatically, so no
 * user-facing timeout discards input. Sessions are never read from the URL.
 */
export function createSupabase(config: RuntimeConfig, origin: string = window.location.origin): SupabaseClient {
  return createClient(origin, config.supabasePublishableKey, {
    auth: {
      storage: window.sessionStorage,
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: false,
    },
  });
}
