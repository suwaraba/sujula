import { Link } from 'react-router-dom';
import { useStore } from '@/store/StoreProvider';
import { Notice } from './ui';
import { humanise } from '@/lib/format';

/**
 * What is standing between this store and a sale.
 *
 * Shown above every screen rather than only on the settings page, because a
 * seller who cannot trade will otherwise write six listings and find out at the
 * end. An unconfirmed pin is included: the goods can be listed, but a collection
 * is being routed to a coordinate that a geocoder guessed from a street name.
 */
export function StoreAlerts() {
  const { store, canTrade, pinNeedsConfirming, collectionPoint } = useStore();
  if (!store) return null;

  const alerts = [];

  if (!canTrade) {
    if (store.kycStatus !== 'VERIFIED') {
      alerts.push(
        <Notice key="kyc" tone="warn" title="Your shop cannot sell yet">
          Verification is {humanise(store.kycStatus).toLowerCase()}.{' '}
          <Link to="/store/verification">Send your documents</Link> to finish opening the shop.
        </Notice>,
      );
    } else {
      alerts.push(
        <Notice key="status" tone="warn" title={`Your shop is ${humanise(store.status).toLowerCase()}`}>
          {store.blockedReason ?? 'Listings stay hidden and new orders cannot reach you until this is resolved.'}
        </Notice>,
      );
    }
  }

  if (canTrade && !collectionPoint?.dispatchable) {
    alerts.push(
      <Notice key="undispatchable" tone="danger" title="No driver can be sent to your shop">
        Your collection address cannot be routed to.{' '}
        <Link to="/store/collection">Check the address and drop a pin</Link> — until then, orders
        for your listings cannot be collected.
      </Notice>,
    );
  } else if (pinNeedsConfirming) {
    alerts.push(
      <Notice key="pin" tone="warn" title="Confirm where your shop actually is">
        Your pin was guessed from the address rather than placed by you, and every listing you
        write inherits it. <Link to="/store/collection">Drop the pin</Link> so drivers arrive at
        the right compound.
      </Notice>,
    );
  }

  if (store.vacationMode) {
    alerts.push(
      <Notice key="vacation" tone="info" title="Your shop is on holiday">
        Buyers can see your listings but cannot order.{' '}
        <Link to="/store">Turn holiday mode off</Link> when you are back.
      </Notice>,
    );
  }

  if (alerts.length === 0) return null;
  return <div className="stack stack--tight" style={{ marginBottom: 'var(--space-4)' }}>{alerts}</div>;
}
