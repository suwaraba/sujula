import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { dashboard as dashboardApi } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import { keys } from '@/hooks';
import { Pill } from './primitives';

/**
 * The navigation is grouped the way the server groups its controllers, because
 * that is also how the work is grouped: the person clearing the dispute queue
 * is not the person drawing a delivery zone.
 *
 * Counts hanging off the dashboard are shown against the queues that have
 * them. A queue with nothing in it should look empty from the sidebar, or
 * everybody opens all of them every morning.
 */
const SECTIONS: {
  title: string;
  items: {
    to: string;
    label: string;
    badge?: (d: DashboardCounts) => number | undefined;
    /**
     * A read support cannot make. Only the audit log: every other screen under
     * `/admin` is readable by both roles, and the difference shows up as
     * disabled buttons rather than a missing page.
     */
    adminOnly?: true;
  }[];
}[] = [
  {
    title: 'Overview',
    items: [{ to: '/', label: 'Dashboard' }],
  },
  {
    title: 'Trade',
    items: [
      { to: '/orders', label: 'Orders' },
      { to: '/payments', label: 'Payments' },
      { to: '/disputes', label: 'Disputes', badge: (d) => d.disputesOpen },
      { to: '/callbacks', label: 'Callbacks', badge: (d) => d.callbacksOutstanding },
    ],
  },
  {
    title: 'Delivery',
    items: [
      { to: '/shipments', label: 'Dispatch board', badge: (d) => d.stuckShipments },
      { to: '/shipments/unassigned', label: 'Unassigned parcels' },
      { to: '/drivers', label: 'Drivers' },
      { to: '/pickup-points', label: 'Pickup points' },
      { to: '/zones', label: 'Zones' },
      { to: '/rate-cards', label: 'Rate cards' },
    ],
  },
  {
    title: 'Money',
    items: [
      { to: '/ledger', label: 'Ledger' },
      { to: '/balances', label: 'Balances' },
      { to: '/payouts', label: 'Payout runs', badge: (d) => d.payoutBatchesAwaitingApproval },
      { to: '/fx', label: 'FX rates and spread' },
      { to: '/reports/revenue', label: 'Revenue' },
      { to: '/reports/exports', label: 'Exports' },
      { to: '/reconciliation', label: 'Reconciliation' },
    ],
  },
  {
    title: 'Moderation',
    items: [
      { to: '/stores', label: 'Stores', badge: (d) => d.storesAwaitingApproval },
      { to: '/kyc', label: 'KYC queue', badge: (d) => d.kycWaiting },
      { to: '/moderation/products', label: 'Listings' },
      { to: '/moderation/reviews', label: 'Reported reviews' },
      { to: '/moderation/cases', label: 'Policy cases', badge: (d) => d.moderationCasesOpen },
    ],
  },
  {
    title: 'Platform',
    items: [
      { to: '/users', label: 'Accounts' },
      { to: '/announcements', label: 'Announcements' },
      { to: '/feature-flags', label: 'Feature flags' },
      { to: '/jobs', label: 'Background jobs' },
      { to: '/audit-log', label: 'Audit log', adminOnly: true },
      { to: '/account', label: 'Your account' },
    ],
  },
];

interface DashboardCounts {
  disputesOpen: number;
  callbacksOutstanding: number;
  stuckShipments: number;
  payoutBatchesAwaitingApproval: number;
  storesAwaitingApproval: number;
  kycWaiting: number;
  moderationCasesOpen: number;
}

export function Layout() {
  const { profile, canDecide, signOut } = useAuth();
  const navigate = useNavigate();
  const [signingOut, setSigningOut] = useState(false);

  // Refetched on a slow beat: the sidebar is not a monitoring screen, and a
  // count that flickers teaches people to stop reading it.
  const { data } = useQuery({
    queryKey: keys.dashboard,
    queryFn: dashboardApi.get,
    refetchInterval: 120_000,
    staleTime: 60_000,
  });

  const counts: DashboardCounts = {
    disputesOpen: data?.disputesOpen ?? 0,
    callbacksOutstanding: data?.callbacksOutstanding ?? 0,
    stuckShipments: data?.stuckShipments ?? 0,
    payoutBatchesAwaitingApproval: data?.payoutBatchesAwaitingApproval ?? 0,
    storesAwaitingApproval: data?.storesAwaitingApproval ?? 0,
    kycWaiting: data?.kycWaiting ?? 0,
    moderationCasesOpen: data?.moderationCasesOpen ?? 0,
  };

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">Sujula</span>
          <span className="brand-sub">Administration</span>
        </div>

        <nav className="nav">
          {SECTIONS.map((section) => (
            <div key={section.title} className="nav-section">
              <p className="nav-section-title">{section.title}</p>
              {section.items.map((item) => {
                // Hidden rather than disabled, because this one is a whole
                // screen rather than an action: a link that always answers 403
                // teaches people to ignore the sidebar.
                if (item.adminOnly && !canDecide) return null;
                const badge = item.badge?.(counts);
                return (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.to === '/' || item.to === '/shipments/unassigned'}
                    className={({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`}
                  >
                    <span>{item.label}</span>
                    {badge ? <span className="nav-badge">{badge}</span> : null}
                  </NavLink>
                );
              })}
            </div>
          ))}
        </nav>

        <div className="sidebar-foot">
          <p className="who">
            {profile?.fullName || profile?.email}
            <br />
            <Pill tone={canDecide ? 'info' : 'neutral'}>
              {canDecide ? 'Administrator' : 'Support — read only'}
            </Pill>
          </p>
          {!canDecide && (
            <p className="who-note">
              You can read every queue here. Decisions — money, statuses, sanctions — are an
              administrator's.
            </p>
          )}
          <button
            type="button"
            className="button button-ghost button-block"
            disabled={signingOut}
            onClick={async () => {
              setSigningOut(true);
              await signOut(false);
              navigate('/');
            }}
          >
            Sign out
          </button>
          <button
            type="button"
            className="link-button"
            onClick={async () => {
              setSigningOut(true);
              await signOut(true);
              navigate('/');
            }}
          >
            Sign out everywhere
          </button>
        </div>
      </aside>

      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
