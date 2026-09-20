import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/api/errors';
import { AuthProvider } from '@/auth/AuthProvider';
import { RequireAccount, RequireAnonymous, RequireVendor } from '@/auth/Gate';
import { StoreProvider } from '@/store/StoreProvider';
import { ToastProvider } from '@/components/Toast';
import { Shell } from '@/components/Shell';
import { EmptyState } from '@/components/ui';

import { SignIn } from '@/screens/SignIn';
import { Register } from '@/screens/Register';
import { ForgotPassword } from '@/screens/ForgotPassword';
import { Apply } from '@/screens/Apply';
import { Dashboard } from '@/screens/Dashboard';
import { Orders } from '@/screens/Orders';
import { OrderDetail } from '@/screens/OrderDetail';
import { Products } from '@/screens/Products';
import { ProductNew } from '@/screens/ProductNew';
import { ProductEdit } from '@/screens/ProductEdit';
import { Inventory } from '@/screens/Inventory';
import { StockHistory } from '@/screens/StockHistory';
import { Earnings } from '@/screens/Earnings';
import { Analytics } from '@/screens/Analytics';
import { StoreSettings } from '@/screens/StoreSettings';
import { StoreCollection } from '@/screens/StoreCollection';
import { StoreVerification } from '@/screens/StoreVerification';
import { StorePayouts } from '@/screens/StorePayouts';
import { StoreStaff } from '@/screens/StoreStaff';
import { Account } from '@/screens/Account';
import { More } from '@/screens/More';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: true,
      retry: (failureCount, error) => {
        // A 4xx is the server's considered answer. Repeating it wastes a round
        // trip and, on a rate-limited endpoint, digs the hole deeper. Only a
        // network failure or a server error is worth another go.
        if (error instanceof ApiError) {
          if (error.status >= 400 && error.status < 500) return false;
        }
        return failureCount < 2;
      },
    },
    mutations: {
      // Never automatic. Every mutation on this surface carries an
      // Idempotency-Key minted by the screen that owns it, and a blind retry
      // here would bypass the reset that follows a failure.
      retry: false,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <Routes>
              {/* Getting in */}
              <Route path="/sign-in" element={<RequireAnonymous><SignIn /></RequireAnonymous>} />
              <Route path="/register" element={<RequireAnonymous><Register /></RequireAnonymous>} />
              <Route path="/forgot-password" element={<ForgotPassword />} />

              {/* An account without a shop. Needs sign-in, must not need a shop. */}
              <Route path="/apply" element={<RequireAccount><Apply /></RequireAccount>} />

              {/* Everything behind the gate shares the shell and the store context. */}
              <Route
                element={
                  <RequireVendor>
                    <StoreProvider>
                      <Shell />
                    </StoreProvider>
                  </RequireVendor>
                }
              >
                <Route path="/" element={<Dashboard />} />
                <Route path="/orders" element={<Orders />} />
                <Route path="/orders/:orderId" element={<OrderDetail />} />
                <Route path="/products" element={<Products />} />
                <Route path="/products/new" element={<ProductNew />} />
                <Route path="/products/:productId" element={<ProductEdit />} />
                <Route path="/inventory" element={<Inventory />} />
                <Route path="/inventory/:variantId/history" element={<StockHistory />} />
                <Route path="/earnings" element={<Earnings />} />
                <Route path="/analytics" element={<Analytics />} />
                <Route path="/store" element={<StoreSettings />} />
                <Route path="/store/collection" element={<StoreCollection />} />
                <Route path="/store/verification" element={<StoreVerification />} />
                <Route path="/store/payouts" element={<StorePayouts />} />
                <Route path="/store/staff" element={<StoreStaff />} />
                <Route path="/account" element={<Account />} />
                <Route path="/more" element={<More />} />
              </Route>

              <Route path="/index.html" element={<Navigate to="/" replace />} />
              <Route path="*" element={<NotFound />} />
            </Routes>
          </ToastProvider>
        </AuthProvider>
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
        action={<a href="/" className="btn btn--primary">Back to your shop</a>}
      />
    </div>
  );
}
