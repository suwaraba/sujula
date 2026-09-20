import { Link } from 'react-router-dom';
import { useAuth } from '@/auth/AuthProvider';
import { useStore } from '@/store/StoreProvider';
import { humanise } from '@/lib/format';
import { Badge, Button, Card, PageHeader } from '@/components/ui';

/**
 * The phone's fifth tab: everything the tab bar has no room for.
 *
 * On a desktop the sidebar carries all of these, so this screen only ever
 * matters on a narrow viewport — which is where most sellers are.
 */
const LINKS: { to: string; label: string; icon: string; description: string }[] = [
  { to: '/inventory', label: 'Stock', icon: '▤', description: 'What you have, and what is running low' },
  { to: '/analytics', label: 'Insights', icon: '◈', description: 'What sold, and how deliveries went' },
  { to: '/store', label: 'Shop details', icon: '⌂', description: 'Name, policies, holiday mode' },
  { to: '/store/collection', label: 'Collection point', icon: '◉', description: 'Where drivers collect and when' },
  { to: '/store/verification', label: 'Verification', icon: '✓', description: 'Identity and business documents' },
  { to: '/store/payouts', label: 'Payout account', icon: '◱', description: 'Where your money is sent' },
  { to: '/store/staff', label: 'Staff', icon: '⊞', description: 'Who else can work in your shop' },
  { to: '/account', label: 'Your account', icon: '☉', description: 'Password, devices, signing out' },
];

export function More() {
  const { me, signOut } = useAuth();
  const { store } = useStore();

  return (
    <div className="page stack">
      <PageHeader
        title={store?.storeName ?? 'Your shop'}
        subtitle={
          store && (
            <>
              <Badge tone={store.canTrade ? 'ok' : 'warn'} dot>{humanise(store.status)}</Badge>{' '}
              <span className="muted">· paid in {store.settlementCurrency}</span>
            </>
          )
        }
      />

      <Card flush>
        <div className="list">
          {LINKS.map((link) => (
            <Link key={link.to} to={link.to} className="list__item">
              <span className="nav__icon" aria-hidden="true">{link.icon}</span>
              <div className="list__main">
                <div className="list__title">{link.label}</div>
                <div className="list__meta">{link.description}</div>
              </div>
              <span className="list__chevron" aria-hidden="true">›</span>
            </Link>
          ))}
        </div>
      </Card>

      <Card>
        <div className="stack stack--tight">
          <div className="small muted">Signed in as {me?.profile.email}</div>
          <Button variant="secondary" block onClick={() => void signOut()}>Sign out</Button>
        </div>
      </Card>
    </div>
  );
}
