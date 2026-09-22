import { NavLink, Outlet } from 'react-router-dom';
import { useCounter } from '@/counter/CounterProvider';
import { useOnline } from '@/lib/hooks';
import { CounterAlerts } from './CounterAlerts';

type Entry = { to: string; label: string; icon: string; end?: boolean };

const ENTRIES: Entry[] = [
  { to: '/', label: 'Counter', icon: '▤', end: true },
  { to: '/incoming', label: 'Coming in', icon: '↓' },
  { to: '/overdue', label: 'To return', icon: '↩' },
  { to: '/earnings', label: 'Earnings', icon: '◫' },
  { to: '/settings', label: 'Counter', icon: '⚙' },
];

export function Shell() {
  const { point, parcels } = useCounter();
  const online = useOnline();

  const incoming = parcels?.incoming.length ?? 0;
  const overdue = parcels?.overdue.length ?? 0;

  const countFor = (to: string) =>
    to === '/incoming' ? incoming : to === '/overdue' ? overdue : 0;

  return (
    <div className="shell">
      <header className="shell__header">
        <div className="shell__brand">
          <span className="shell__mark" aria-hidden="true">S</span>
          <span>Counter</span>
        </div>
        <div className="shell__spacer" />
        <div className="shell__point truncate" title={point?.name ?? undefined}>
          {point?.name ?? ''}
        </div>
      </header>

      {!online && (
        <div className="offline-bar" role="status">
          No connection. Nothing can be taken in or handed over until it is back.
        </div>
      )}

      <div className="shell__body">
        <nav className="shell__nav" aria-label="Sections">
          {ENTRIES.map((entry) => (
            <NavLink
              key={entry.to}
              to={entry.to}
              end={entry.end}
              className={({ isActive }) => (isActive ? 'nav__item is-active' : 'nav__item')}
            >
              <span className="nav__icon" aria-hidden="true">{entry.icon}</span>
              <span>{entry.to === '/settings' ? 'Settings' : entry.label}</span>
              {countFor(entry.to) > 0 && <span className="nav__count">{countFor(entry.to)}</span>}
            </NavLink>
          ))}
        </nav>

        <main className="shell__main">
          <CounterAlerts />
          <Outlet />
        </main>
      </div>

      <nav className="shell__tabbar" aria-label="Sections">
        {ENTRIES.map((entry) => (
          <NavLink
            key={entry.to}
            to={entry.to}
            end={entry.end}
            className={({ isActive }) => (isActive ? 'tabbar__item is-active' : 'tabbar__item')}
          >
            <span className="tabbar__icon" aria-hidden="true">{entry.icon}</span>
            <span>{entry.to === '/settings' ? 'Settings' : entry.label}</span>
            {countFor(entry.to) > 0 && (
              <span className="tabbar__badge">{countFor(entry.to)}</span>
            )}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
