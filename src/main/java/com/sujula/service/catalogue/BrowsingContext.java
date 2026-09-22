package com.sujula.service.catalogue;

import com.sujula.model.constant.GeocodeConfidence;

/**
 * The two answers a catalogue read needs, kept apart on purpose.
 *
 * <p>This is C1 expressed as a type. A buyer in Madrid sending a phone to their
 * sister in Serrekunda is the case this marketplace exists for, and for that
 * buyer the two questions have different answers:
 *
 * <ul>
 *   <li><strong>Where are the goods going?</strong> Serrekunda. This decides
 *       which products can be delivered at all, which rank near, what shipping
 *       costs, and which pickup points are offered.</li>
 *   <li><strong>What should the prices say?</strong> Euro. This decides nothing
 *       about delivery and everything about display.</li>
 * </ul>
 *
 * <p>Holding them in one object is safe only because they are separately named
 * and separately sourced. The delivery half comes from a delivery context the
 * shopper established — never from their IP. The payer half comes from the
 * browser and the IP — never from the delivery country. There is deliberately no
 * method here that derives one from the other, and adding one would be the bug
 * C1 exists to prevent: a Madrid buyer quoted in dalasi because the parcel is
 * going to Gambia, or a phone in Banjul ranked as far away because the person
 * paying for it is in Spain.
 *
 * @param deliveryLatitude  where the goods go. Null when the shopper has not
 *                          said yet, which is the normal state of a first page
 *                          view and not an error
 * @param deliveryCountry   the delivery country, which may be known even when
 *                          the exact point is not
 * @param displayCurrency   what prices are shown in. Always resolved — a
 *                          storefront that renders no prices until an IP lookup
 *                          succeeds is worse than one that opens in its home
 *                          currency
 */
public record BrowsingContext(
        Double deliveryLatitude,
        Double deliveryLongitude,
        String deliveryCountry,
        GeocodeConfidence deliveryConfidence,
        String deliveryContextId,
        String displayCurrency,
        String language) {

    /** Whether the goods have somewhere specific to go. */
    public boolean hasDeliveryPoint() {
        return deliveryLatitude != null && deliveryLongitude != null;
    }

    /** Whether anything at all is known about the destination. */
    public boolean hasDestination() {
        return hasDeliveryPoint() || (deliveryCountry != null && !deliveryCountry.isBlank());
    }

    /**
     * Whether the pin is good enough to rank a catalogue against.
     *
     * <p>A point resolved only to the middle of a town will rank every product
     * in that town as equidistant, which is not wrong so much as uninformative.
     * Ranking still happens — a town centre beats nothing — but a caller that
     * wants to say "confirm where this is going" has something to test.
     */
    public boolean hasPreciseDeliveryPoint() {
        return hasDeliveryPoint()
                && deliveryConfidence != null
                && deliveryConfidence.isDispatchable();
    }
}
