import { Link } from 'react-router-dom';
import { useStore } from '@/store/StoreProvider';
import { Notice } from './ui';

/**
 * Where this listing will be collected from.
 *
 * A seller never types an address onto a product, and the form should say why
 * rather than leaving a silent gap. The server stamps every new listing with
 * the store's pickup pin — falling back to the store address — and that
 * coordinate is the origin every delivery leg is priced and routed from.
 *
 * Which means a store with a guessed pin quietly produces a catalogue of
 * listings with guessed pins. That is worth saying on the form that creates
 * them, not only on the settings page nobody has opened.
 */
export function CollectionPointNote() {
  const { collectionPoint, pinNeedsConfirming, store } = useStore();

  if (!collectionPoint) {
    return (
      <Notice tone="warn" title="Your shop has no collection address">
        Every listing is collected from your shop's address, and yours is not set.{' '}
        <Link to="/store/collection">Set it</Link> before you put anything on sale.
      </Notice>
    );
  }

  const where = [collectionPoint.street, collectionPoint.city, collectionPoint.countryCode]
    .filter(Boolean)
    .join(', ');

  const usingPickup = store?.pickupAddress != null;

  if (!collectionPoint.dispatchable) {
    return (
      <Notice tone="danger" title="No driver can be routed to your shop">
        This listing would be collected from <strong>{where}</strong>, which cannot currently be
        reached. <Link to="/store/collection">Fix the address</Link> — until then, orders for it
        cannot be collected.
      </Notice>
    );
  }

  if (pinNeedsConfirming) {
    return (
      <Notice tone="warn" title="This listing inherits a guessed pin">
        It will be collected from <strong>{where}</strong>, but that pin was worked out from the
        street name rather than placed by you.{' '}
        <Link to="/store/collection">Drop the pin properly</Link> and every listing you have
        written picks it up.
      </Notice>
    );
  }

  return (
    <Notice tone="info" title="Collected from your shop">
      <strong>{where}</strong>
      {usingPickup && ' (your collection point, not your shop address)'} — taken from your shop, so
      there is nothing to type here. <Link to="/store/collection">Change it</Link> if the goods
      are kept somewhere else.
    </Notice>
  );
}
