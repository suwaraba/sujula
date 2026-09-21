import { NavLink, Outlet } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ordersApi } from '@/api/endpoints/orders';
import { useAuth } from '@/auth/AuthProvider';
import { useStore } from '@/store/StoreProvider';
import { StoreAlerts } from './StoreAlerts';

type NavEntry = { to: string; label: string; icon: string; end?: boolean };

const PRIMARY: NavEntry[] = [
  { to: '/', label: 'Today', icon: '◧', end: true },
  { to: '/orders', label: 'Orders', icon: '▣' },
  { to: '/products', label: 'Products', icon: '☰' },
  { to: '/earnings', label: 'Earnings', icon: '◫' },
  { to: '/more', label: 'More', icon: '⋯' },
];

const SIDEBAR: { section: string; items: NavEntry[] }[] = [
  {
    section: 'Selling',
    items: [
      { to: '/', label: 'Today', icon: '◧', end: true },
      { to: '/orders', label: 'Orders', icon: '▣' },
      { to: '/products', label: 'Products', icon: '☰' },
      { to: '/inventory', label: 'Stock', icon: '▤' },
      { to: '/handsets', label: 'Handsets', icon: '▥' },
    ],
  },
  {
    section: 'Offers',
    items: [
      { to: '/promotions', label: 'Promotions', icon: '◇' },
      { to: '/coupons', label: 'Coupons', icon: '◷' },
    ],
  },
  {
    section: 'Money',
    items: [
      { to: '/earnings', label: 'Earnings', icon: '◫' },
      { to: '/analytics', label: 'Insights', icon: '◈' },
    ],
  },
  {
    section: 'Shop',
    items: [
      { to: '/store', label: 'Shop details', icon: '⌂' },
      { to: '/store/collection', label: 'Collection point', icon: '◉' },
      { to: '/store/verification', label: 'Verification', icon: '✓' },
      { to: '/store/payouts', label: 'Payout account', icon: '◱' },
      { to: '/store/staff', label: 'Staff', icon: '⊞' },
      { to: '/account', label: 'Your account', icon: '☉' },
    ],
  },
];

/**
 * The badge count: orders nobody has accepted yet.
 *
 * Deliberately PENDING rather than `awaitingAction`, which also counts orders
 * already packed and waiting for a driver. A badge that never clears because
 * a parcel is sitting on the bench is a badge people stop reading.
 */
function useAwaitingCount(): number {
  const { canTrade } = useStore();
  const { data } = useQuery({
    queryKey: ['orders', 'stats'],
    queryFn: () => ordersApi.stats(),
    enabled: canTrade,
    refetchInterval: 60_000,
    staleTime: 30_000,
  });
  return data?.ordersByStatus?.PENDING ?? 0;
}

export function Shell() {
  const { me } = useAuth();
  const { store } = useStore();
  const awaiting = useAwaitingCount();

  return (
    <div className="shell">
      <header className="shell__header">
        <div className="shell__brand">
          <span className="shell__brand-mark" aria-hidden="true">S</span>
          <span>Sujula</span>
        </div>
        <div className="shell__spacer" />
        <div className="shell__store truncate" title={store?.storeName ?? undefined}>
          {store?.storeName ?? me?.profile.fullName ?? ''}
        </div>
      </header>

      <div className="shell__body">
        <nav className="shell__nav" aria-label="Sections">
          {SIDEBAR.map(({ section, items }) => (
            <div key={section}>
              <div className="nav__section">{section}</div>
              {items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) => (isActive ? 'nav__item is-active' : 'nav__item')}
                >
                  <span className="nav__icon" aria-hidden="true">{item.icon}</span>
                  <span>{item.label}</span>
                  {item.to === '/orders' && awaiting > 0 && (
                    <span className="nav__count">{awaiting}</span>
                  )}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <main className="shell__main">
          <StoreAlerts />
          <Outlet />
        </main>
      </div>

      <nav className="shell__tabbar" aria-label="Sections">
        {PRIMARY.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) => (isActive ? 'tabbar__item is-active' : 'tabbar__item')}
          >
            <span className="tabbar__icon" aria-hidden="true">{item.icon}</span>
            <span>{item.label}</span>
            {item.to === '/orders' && awaiting > 0 && (
              <span className="tabbar__badge">{awaiting > 99 ? '99+' : awaiting}</span>
            )}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
