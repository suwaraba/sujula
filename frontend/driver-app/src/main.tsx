import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { App } from './App';
import { ErrorBoundary } from './ErrorBoundary';
import { SessionProvider } from './auth/session';
import { I18nProvider } from './i18n';
import { ToastProvider } from './components/ui';
import { ApiError } from './api/http';
import './index.css';

/**
 * The query client.
 *
 * `gcTime` is short and nothing is persisted. These responses carry
 * recipients' addresses and phone numbers and the backend serves every one of
 * them `no-store`; a query cache written to disk would quietly undo that on
 * exactly the phone where it matters.
 *
 * Retries stop at the errors that will never change: a 401 needs a new token,
 * a 403 needs dispatch, and a 404 on this surface means the row is not this
 * driver's — none of which a second attempt fixes, and all of which cost a
 * driver's data bundle to ask again.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (attempt, error) =>
        attempt < 2 && !(error instanceof ApiError && [400, 401, 403, 404].includes(error.status)),
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 15_000),
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: true,
      networkMode: 'online',
    },
    mutations: {
      // Custody events never come through here — they go to the outbox, which
      // has its own durable retry. A mutation that failed on this path is one
      // the driver can see and tap again.
      retry: 0,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <I18nProvider>
        <QueryClientProvider client={queryClient}>
          <SessionProvider>
            <ToastProvider>
              <BrowserRouter>
                <App />
              </BrowserRouter>
            </ToastProvider>
          </SessionProvider>
        </QueryClientProvider>
      </I18nProvider>
    </ErrorBoundary>
  </StrictMode>,
);

// The shell, so the app opens in a village with no coverage. It caches the
// document and the hashed assets and deliberately nothing else — see public/sw.js.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').catch(() => undefined);
  });
}
