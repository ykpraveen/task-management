import { createApiClients } from '@task-management/api-client';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App, createAppRouter, createQueryClient } from './app';
import { SessionController } from './auth/session';
import { createSupabase, loadRuntimeConfig } from './auth/supabase';
import './styles/tokens.css';
import './styles/app.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Missing #root element');
}

const queryClient = createQueryClient();
const supabase = createSupabase(await loadRuntimeConfig());
const session = new SessionController(supabase, queryClient);
await session.init();
const api = createApiClients({
  getAccessToken: () => session.accessToken(),
  onUnauthenticated: () => session.handleUnauthenticated(),
});
const router = createAppRouter({ queryClient, session, api });

createRoot(container).render(
  <StrictMode>
    <App queryClient={queryClient} router={router} />
  </StrictMode>,
);
