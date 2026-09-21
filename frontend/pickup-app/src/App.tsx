import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/api/errors';
import { SessionProvider } from '@/auth/session';
import { RequireAccount, RequireAnonymous, RequireOperator } from '@/auth/Gate';
import { CounterProvider } from '@/counter/CounterProvider';
import { ToastProvider } from '@/components/Toast';
import { Shell } from '@/components/Shell';
import { EmptyState } from '@/components/ui';

import { SignIn } from '@/screens/SignIn';
import { Apply } from '@/screens/Apply';
import { Counter } from '@/screens/Counter';
import { Incoming } from '@/screens/Incoming';
import { Overdue } from '@/screens/Overdue';
import { Earnings } from '@/screens/Earnings';
import { Settings } from '@/screens/Settings';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 20_000,
      refetchOnWindowFocus: true,
      // A counter tablet wakes up when somebody walks in; what it shows then
      // should be current, not whatever it had before it slept.
      refetchOnReconnect: true,
      retry: (failureCount, error) => {
        // A 4xx is the server's considered answer — a wrong code, a full
        // shelf, a deadline not yet reached. Repeating it wastes a round trip
        // with a driver waiting.
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
        return failureCount < 2;
      },
    },
    mutations: {
      // Never automatic. Every custody action carries a client event id, and a
      // blind retry would bypass the reset that follows a refusal.
      retry: false,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <SessionProvider>
          <ToastProvider>
            <Routes>
              <Route path="/sign-in" element={<RequireAnonymous><SignIn /></RequireAnonymous>} />

              {/* An account without a counter. Needs a session, must not need a counter. */}
              <Route path="/apply" element={<RequireAccount><Apply /></RequireAccount>} />

              <Route
                element={
                  <RequireOperator>
                    <CounterProvider>
                      <Shell />
                    </CounterProvider>
                  </RequireOperator>
                }
              >
                <Route path="/" element={<Counter />} />
                <Route path="/incoming" element={<Incoming />} />
                <Route path="/overdue" element={<Overdue />} />
                <Route path="/earnings" element={<Earnings />} />
                <Route path="/settings" element={<Settings />} />
              </Route>

              <Route path="/index.html" element={<Navigate to="/" replace />} />
              <Route path="*" element={<NotFound />} />
            </Routes>
          </ToastProvider>
        </SessionProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

function NotFound() {
  return (
    <div className="auth-screen">
      <EmptyState
        icon="∅"
        title="There is nothing here"
        action={<a href="/" className="btn btn--primary">Back to the counter</a>}
      />
    </div>
  );
}
