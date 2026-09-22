package com.sujula.model.constant;

/**
 * How much to trust the pin on an address.
 *
 * <p>A geocoder always returns coordinates. What varies, and what matters for a
 * marketplace that prices delivery by distance, is whether those coordinates are
 * the building or the middle of the town it is in. A rider sent to the centroid
 * of Serekunda has not been sent anywhere, and pricing a leg from it can be
 * wrong by kilometres — so the confidence travels with the address rather than
 * being discarded the moment the pin is stored.
 *
 * <p>The ordering is deliberate: {@link #atLeast} lets a caller ask for "good
 * enough to dispatch on" without repeating the list.
 */
public enum GeocodeConfidence {

    /** Nothing was resolved. Delivery falls back to a scope distance. */
    NONE,

    /**
     * The town, the district or the country — not the address. Common for rural
     * Gambia and for anywhere without street-level coverage, and the reason
     * {@code confirm-pin} exists.
     */
    APPROXIMATE,

    /** A geometric centre: the right street or block, not the right door. */
    CENTROID,

    /** Interpolated along a street from the numbers either side of it. */
    INTERPOLATED,

    /** The building itself. */
    EXACT,

    /**
     * The person who lives there moved the pin and said it was right.
     *
     * <p>Ranked above {@link #EXACT} because it is better evidence: a rooftop
     * match is a database's opinion about an address, and this is the resident's
     * about their own home. It is also never overwritten by a re-geocode — a
     * confirmed pin outranks whatever the geocoder says next time.
     */
    USER_CONFIRMED;

    /** Whether this is at least as trustworthy as {@code floor}. */
    public boolean atLeast(GeocodeConfidence floor) {
        return ordinal() >= floor.ordinal();
    }

    /**
     * Good enough to send a rider to without asking the buyer to check the map.
     *
     * <p>The line sits below {@link #INTERPOLATED} rather than at {@link #EXACT}
     * because street-number interpolation is accurate to a few doors, which a
     * rider resolves by looking, whereas a centroid can be a kilometre out.
     */
    public boolean isDispatchable() {
        return atLeast(INTERPOLATED);
    }

    /** Whether the client should ask the buyer to confirm the pin on a map. */
    public boolean needsConfirmation() {
        return !isDispatchable();
    }
}
