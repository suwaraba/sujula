package com.sujula.service.delivery;

/**
 * Great-circle distance, in the one place everything that needs it can reach.
 *
 * <p>Straight-line, not road distance, and the difference is not small: a rider
 * crossing Serekunda covers noticeably more ground than the crow. That is
 * deliberate. Road distance means a routing API call per leg — several per
 * basket, on every cart view — and the rate card's per-kilometre figure is
 * calibrated against these numbers, so the two errors cancel where it matters.
 * What this must never be is <em>inconsistent</em>: two copies of the formula
 * would eventually disagree, and a shipping estimate that differs from what
 * checkout charges is the kind of discrepancy a shopper remembers.
 */
public final class Distances {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private Distances() {}

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
