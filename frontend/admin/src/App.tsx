import { useEffect } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { reference } from '@/api/endpoints';
import { AuthProvider } from '@/auth/AuthContext';
import { RequireStaff } from '@/auth/guards';
import { Layout } from '@/components/Layout';
import { EmptyState, PageHeader } from '@/components/primitives';
import { loadCurrencyCatalogue } from '@/money/currency';
import { keys } from '@/hooks';

import { DashboardPage } from '@/pages/Dashboard';
import { AccountPage } from '@/pages/AccountPage';
import { OrdersPage } from '@/pages/orders/OrdersPage';
import { OrderDetailPage } from '@/pages/orders/OrderDetailPage';
import { ShipmentsPage } from '@/pages/shipments/ShipmentsPage';
import { UnassignedPage } from '@/pages/shipments/UnassignedPage';
import { CustodyChainPage } from '@/pages/shipments/CustodyChainPage';
import { PaymentsPage } from '@/pages/money/PaymentsPage';
import { PaymentDetailPage } from '@/pages/money/PaymentDetailPage';
import { LedgerPage } from '@/pages/money/LedgerPage';
import { ReconciliationPage } from '@/pages/money/ReconciliationPage';
import { BalancesPage } from '@/pages/money/BalancesPage';
import { PayoutsPage } from '@/pages/money/PayoutsPage';
import { BatchDetailPage } from '@/pages/money/BatchDetailPage';
import { FxPage } from '@/pages/money/FxPage';
import { ExportsPage, RevenuePage } from '@/pages/money/ReportsPages';
import { DisputesPage } from '@/pages/disputes/DisputesPage';
import { DisputeDetailPage } from '@/pages/disputes/DisputeDetailPage';
import { CallbacksPage } from '@/pages/disputes/CallbacksPage';
import { StoresPage } from '@/pages/moderation/StoresPage';
import { KycPage } from '@/pages/moderation/KycPage';
import { ProductsPage } from '@/pages/moderation/ProductsPage';
import { ReviewsPage } from '@/pages/moderation/ReviewsPage';
import { CasesPage } from '@/pages/moderation/CasesPage';
import { UsersPage } from '@/pages/users/UsersPage';
import { UserDetailPage } from '@/pages/users/UserDetailPage';
import { DriversPage } from '@/pages/logistics/DriversPage';
import { PickupPointsPage } from '@/pages/logistics/PickupPointsPage';
import { RateCardsPage } from '@/pages/logistics/RateCardsPage';
import { ZoneDetailPage, ZonesPage } from '@/pages/logistics/ZonesPage';
import { FeatureFlagsPage } from '@/pages/platform/FeatureFlagsPage';
import { JobsPage } from '@/pages/platform/JobsPage';
import { AnnouncementsPage } from '@/pages/platform/AnnouncementsPage';
import { AuditLogPage } from '@/pages/platform/AuditLogPage';

/**
 * Loads the currency catalogue before anything formats money.
 *
 * `CurrencyCatalogue` is the server's authority on how many minor units each
 * currency has, and it is published on `GET /currencies` — which is public, so
 * this can run before there is a session. Until it lands, `formatMoney` falls
 * back to `Intl`, which is right for XOF and JPY too; the fetch is what makes
 * the platform's own answer authoritative rather than the browser's.
 */
function CurrencyCatalogueLoader() {
  const { data } = useQuery({
    queryKey: keys.currencies,
    queryFn: reference.currencies,
    staleTime: 60 * 60 * 1000,
  });

  useEffect(() => {
    if (data) loadCurrencyCatalogue(data.currencies);
  }, [data]);

  return null;
}

function NotFound() {
  return (
    <>
      <PageHeader title="Nothing here" />
      <EmptyState>
        That screen does not exist. If you followed a link from somewhere in the console, say where —
        a dead link here usually means a row was deleted rather than that the page is missing.
      </EmptyState>
    </>
  );
}

export function App() {
  return (
    <AuthProvider>
      <CurrencyCatalogueLoader />
      <RequireStaff>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<DashboardPage />} />

            <Route path="orders" element={<OrdersPage />} />
            <Route path="orders/:orderId" element={<OrderDetailPage />} />

            <Route path="shipments" element={<ShipmentsPage />} />
            <Route path="shipments/unassigned" element={<UnassignedPage />} />
            <Route path="shipments/:shipmentId/custody-chain" element={<CustodyChainPage />} />

            <Route path="payments" element={<PaymentsPage />} />
            <Route path="payments/:paymentId" element={<PaymentDetailPage />} />

            <Route path="ledger" element={<LedgerPage />} />
            <Route path="reconciliation" element={<ReconciliationPage />} />
            <Route path="balances" element={<BalancesPage />} />
            <Route path="payouts" element={<PayoutsPage />} />
            <Route path="payouts/:batchId" element={<BatchDetailPage />} />
            <Route path="fx" element={<FxPage />} />
            <Route path="reports/revenue" element={<RevenuePage />} />
            <Route path="reports/exports" element={<ExportsPage />} />

            <Route path="disputes" element={<DisputesPage />} />
            <Route path="disputes/:disputeId" element={<DisputeDetailPage />} />
            <Route path="callbacks" element={<CallbacksPage />} />

            <Route path="stores" element={<StoresPage />} />
            <Route path="kyc" element={<KycPage />} />
            <Route path="moderation/products" element={<ProductsPage />} />
            <Route path="moderation/reviews" element={<ReviewsPage />} />
            <Route path="moderation/cases" element={<CasesPage />} />

            <Route path="users" element={<UsersPage />} />
            <Route path="users/:userId" element={<UserDetailPage />} />

            <Route path="drivers" element={<DriversPage />} />
            <Route path="pickup-points" element={<PickupPointsPage />} />
            <Route path="zones" element={<ZonesPage />} />
            <Route path="zones/:zoneId" element={<ZoneDetailPage />} />
            <Route path="rate-cards" element={<RateCardsPage />} />

            <Route path="announcements" element={<AnnouncementsPage />} />
            <Route path="feature-flags" element={<FeatureFlagsPage />} />
            <Route path="jobs" element={<JobsPage />} />
            <Route path="audit-log" element={<AuditLogPage />} />

            <Route path="account" element={<AccountPage />} />

            <Route path="dashboard" element={<Navigate to="/" replace />} />
            <Route path="*" element={<NotFound />} />
          </Route>
        </Routes>
      </RequireStaff>
    </AuthProvider>
  );
}
