/**
 * The frame every screen sits in: a title bar, the tab bar, and the strip that
 * tells the driver the connection is gone.
 *
 * The offline strip is the one piece here that is doing real work. A driver who
 * cannot tell "saved on this phone" from "sent" will tap a second time, and a
 * second tap on a handover is a second handover. So the state of the connection
 * and the size of the queue are shown at the top of every screen, all the time,
 * rather than in a settings page nobody opens.
 */

import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useOnline } from '../lib/connectivity';
import { useT } from '../i18n';
import * as outbox from '../offline/outbox';

export function TopBar({
  title,
  onBack,
  action,
}: {
  title: string;
  onBack?: (() => void) | undefined;
  action?: ReactNode;
}) {
  const navigate = useNavigate();
  return (
    <header className="topbar">
      {onBack !== undefined ? (
        <button
          type="button"
          className="iconbutton"
          onClick={() => (onBack ? onBack() : navigate(-1))}
          aria-label="Back"
        >
          ‹
        </button>
      ) : null}
      <h1 className="topbar__title">{title}</h1>
      {action}
    </header>
  );
}

/** How many records this phone is still holding. */
export function usePendingCount(): number {
  const [count, setCount] = useState(0);
  useEffect(
    () =>
      outbox.subscribe((pending) => {
        setCount(pending.filter((event) => event.state !== 'rejected').length);
      }),
    [],
  );
  return count;
}

export function ConnectionBar() {
  const online = useOnline();
  const pending = usePendingCount();
  const t = useT();

  if (online && pending === 0) return null;

  return (
    <div className="offline-bar" role="status">
      <span aria-hidden="true">{online ? '⬆️' : '📴'}</span>
      <span>{online ? t('app.pending', { count: pending }) : t('app.offline')}</span>
    </div>
  );
}

const TABS = [
  { to: '/jobs', icon: '📦', key: 'nav.jobs' },
  { to: '/earnings', icon: '💵', key: 'nav.earnings' },
  { to: '/history', icon: '✅', key: 'nav.history' },
  { to: '/me', icon: '👤', key: 'nav.profile' },
] as const;

export function Tabs() {
  const t = useT();
  const pending = usePendingCount();

  return (
    <nav className="tabs" aria-label="Main">
      {TABS.map((tab) => (
        <NavLink key={tab.to} to={tab.to} className="tabs__tab">
          <span className="tabs__icon" aria-hidden="true">
            {tab.icon}
          </span>
          {tab.to === '/me' && pending > 0 && (
            <span className="tabs__badge" aria-hidden="true">
              {pending}
            </span>
          )}
          <span>{t(tab.key)}</span>
        </NavLink>
      ))}
    </nav>
  );
}
