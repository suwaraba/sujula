package com.sujula.model.constant;

/**
 * Where a stored exchange rate came from.
 *
 * <p>Kept alongside the rate because "what was this converted at" and "why was
 * it that number" are different questions, and the second one is the one asked
 * when a vendor disputes a payout. A rate honoured from a quote the buyer was
 * shown is defensible in a way that a rate read from a table at an unrecorded
 * moment is not.
 */
public enum FxSource {

    /**
     * The same currency on both sides — the rate is one, and no lookup happened.
     *
     * <p>Recorded rather than left null so that "no conversion took place" is a
     * positive fact rather than an absence that could equally mean nobody wrote
     * it down.
     */
    IDENTITY,

    /** The latest published rate at the moment the order was priced. */
    PUBLISHED_RATE,

    /**
     * A rate the buyer was shown and which was held for them.
     *
     * <p>The strongest provenance available: the figure charged is the figure
     * quoted, and the quote row still exists to prove it.
     */
    HELD_QUOTE
}
