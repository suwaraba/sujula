package com.sujula.model.constant;

/**
 * What was done to somebody, and for how long.
 *
 * <p>A sanction is the record; the effect on the account is the consequence.
 * That order matters: a user who is suspended has a row saying who suspended
 * them, why, until when and under which case — and the {@code enabled} flag on
 * their account is derived from it. A flag set directly is a punishment nobody
 * can explain six months later, and the person it was applied to is the one who
 * will ask.
 */
public enum SanctionType {

    /**
     * Told, and nothing else.
     *
     * <p>Worth having as a real row rather than a note: three warnings is a
     * pattern, and a pattern is what justifies the next step being heavier.
     */
    WARNING,

    /** Stopped from doing one thing — listing, reviewing, messaging. */
    FEATURE_RESTRICTION,

    /** Shut out for a while. Ends by itself. */
    SUSPENSION,

    /**
     * Shut out with no end date.
     *
     * <p>Distinct from a suspension with a very long duration, because the two
     * are different promises. A suspension says "come back on the 4th"; this
     * says "we are not expecting you back", and somebody has to decide to lift
     * it.
     */
    BAN;

    /** Whether this stops the account working at all. */
    public boolean locksTheAccount() {
        return this == SUSPENSION || this == BAN;
    }

    /** Whether it ends on its own. */
    public boolean expires() {
        return this == SUSPENSION || this == FEATURE_RESTRICTION;
    }
}
