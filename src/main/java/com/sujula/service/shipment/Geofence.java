package com.sujula.service.shipment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How far the driver was from where they said they were.
 *
 * <p>A geofence on this platform is a piece of evidence rather than a gate, and
 * the distinction matters. Most of the destinations here do not resolve to a
 * street address at all — the coordinates came from a buyer's phone in Madrid
 * pointing at their sister's compound, or from a geocoder's best guess — so a
 * hard radius that refused delivery would refuse the ordinary case.
 *
 * <p>So the distance is computed, stored on the event, and used to decide
 * whether the handover is <em>attested</em> or merely <em>claimed</em>. A
 * delivery at four metres and one at four hundred both happened; only one of
 * them is worth anything in a dispute, and the chain says which.
 */
public final class Geofence {

    private Geofence() {}

    /** Earth's mean radius in metres. */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /**
     * Inside this, a handover is taken as attested.
     *
     * <p>Generous on purpose. A phone under a tin roof in the rains reports a
     * fix hundreds of metres out, and a driver who is genuinely at the door
     * should not be told they are not.
     */
    public static final double DEFAULT_RADIUS_M = 250;

    /**
     * Beyond this, the position is not evidence of anything.
     *
     * <p>Recorded rather than refused — the parcel may genuinely have been
     * handed over — but flagged, because a "delivery" logged two kilometres from
     * the address is the shape of a driver marking a round complete from home.
     */
    public static final double IMPLAUSIBLE_M = 2_000;

    /**
     * Metres between two points, or null when either is unknown.
     *
     * <p>Null rather than a large number: "we do not know where this happened"
     * and "this happened a long way away" are different facts about a delivery,
     * and a chain that conflated them would let a phone with no GPS look like a
     * driver at the wrong address.
     */
    public static BigDecimal metresBetween(Double fromLat, Double fromLng,
                                           Double toLat, Double toLng) {
        if (fromLat == null || fromLng == null || toLat == null || toLng == null) {
            return null;
        }
        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(toLng - fromLng);

        // Haversine. Adequate at these distances and free of the singularities
        // the spherical law of cosines has for points a few metres apart, which
        // is exactly the range a handover happens in.
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return BigDecimal.valueOf(EARTH_RADIUS_M * c).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Whether a reported position attests to being at a place.
     *
     * <p>The device's own accuracy is added to the allowance: a fix that admits
     * to being 300 metres uncertain cannot be held to a 250 metre radius, and
     * penalising it would punish the honest phone over the one that reports no
     * accuracy at all.
     */
    public static boolean isWithin(BigDecimal distanceMetres, BigDecimal accuracyMetres,
                                   double radiusMetres) {
        if (distanceMetres == null) {
            return false;   // unknown is not inside
        }
        double allowance = radiusMetres
                + (accuracyMetres == null ? 0 : Math.min(accuracyMetres.doubleValue(), IMPLAUSIBLE_M));
        return distanceMetres.doubleValue() <= allowance;
    }

    /** Whether a position is far enough out to be worth a human looking at it. */
    public static boolean isImplausible(BigDecimal distanceMetres) {
        return distanceMetres != null && distanceMetres.doubleValue() > IMPLAUSIBLE_M;
    }
}
