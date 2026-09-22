package com.sujula.model.constant;

/** Where a promotion is in its life. */
public enum PromotionStatus {

    /** Being set up. Costs nothing and discounts nothing. */
    DRAFT,

    /**
     * Live, or waiting for its window to open.
     *
     * <p>One status rather than two, because "scheduled" and "running" are the
     * same decision by the seller and differ only by the clock. Whether it
     * actually applies is a question about {@code startsAt}/{@code endsAt}, and
     * asking the clock is more honest than a field somebody has to remember to
     * flip at midnight.
     */
    ACTIVE,

    /** Stopped by the seller before its window closed. */
    PAUSED,

    /** Its window has closed. Kept, because orders reference it. */
    EXPIRED,

    /** Cancelled. Kept for the same reason. */
    CANCELLED;

    public boolean isRunnable() {
        return this == ACTIVE;
    }
}
