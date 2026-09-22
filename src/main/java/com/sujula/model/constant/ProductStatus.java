package com.sujula.model.constant;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Where a listing is in its life.
 *
 * <p>A marketplace that lets a seller publish straight to the catalogue is a
 * marketplace that will, sooner or later, be selling something it should not.
 * So the ladder is: the seller writes a DRAFT, sends it for review, and only a
 * listing a moderator has APPROVED can be PUBLISHED — by the seller, when they
 * are ready, which is a separate decision from being allowed to.
 *
 * <p>The three states off the main ladder are the ones that matter in practice.
 * REJECTED is a moderator's no, with a reason. SUSPENDED is the platform pulling
 * something already live, which only the platform can undo. ARCHIVED is the
 * seller's own end of the line, and it is terminal: a listing that has been
 * ordered cannot be deleted, because an order line points at it and a buyer's
 * receipt has to keep resolving.
 */
public enum ProductStatus {

    /** Being written. Visible only to the seller, and never to a buyer. */
    DRAFT,

    /** Sent for moderation, and frozen while it waits — see {@link #isEditable}. */
    IN_REVIEW,

    /**
     * Passed moderation, not yet on sale.
     *
     * <p>Separate from PUBLISHED because being allowed to sell and choosing to
     * are different decisions. A seller who gets approval overnight should not
     * find their listing live before they have set the stock.
     */
    APPROVED,

    /** On sale. The only status a buyer can see. */
    PUBLISHED,

    /** Taken down by the seller. Approval survives, so it can go back up. */
    UNPUBLISHED,

    /** Refused, with a reason the seller can act on. Editing returns it to DRAFT. */
    REJECTED,

    /**
     * Pulled by the platform. The seller cannot publish out of this, which is
     * the point of it existing separately from UNPUBLISHED.
     */
    SUSPENDED,

    /** The seller is finished with it. Terminal. */
    ARCHIVED;

    /** Whether a buyer can see and order this. */
    public boolean isLive() {
        return this == PUBLISHED;
    }

    /**
     * Whether the seller may change the listing's content.
     *
     * <p>Not while a moderator is looking at it: a listing edited mid-review
     * gets a decision about text that no longer exists, which is how something
     * unapproved ends up approved. And not once it is archived or suspended.
     */
    public boolean isEditable() {
        return this != IN_REVIEW && this != ARCHIVED && this != SUSPENDED;
    }

    /** Whether a moderator has passed this listing's current content. */
    public boolean isApproved() {
        return this == APPROVED || this == PUBLISHED || this == UNPUBLISHED;
    }

    /** Statuses a seller can move to themselves, from this one. */
    public Set<ProductStatus> allowedNext() {
        return Collections.unmodifiableSet(switch (this) {
            case DRAFT      -> EnumSet.of(IN_REVIEW, ARCHIVED);
            case IN_REVIEW  -> EnumSet.of(ARCHIVED);
            case APPROVED   -> EnumSet.of(PUBLISHED, IN_REVIEW, ARCHIVED);
            case PUBLISHED  -> EnumSet.of(UNPUBLISHED, IN_REVIEW, ARCHIVED);
            case UNPUBLISHED-> EnumSet.of(PUBLISHED, IN_REVIEW, ARCHIVED);
            case REJECTED   -> EnumSet.of(DRAFT, IN_REVIEW, ARCHIVED);
            // Only a moderator lifts a suspension, and nothing comes back from
            // archived. Both are deliberately dead ends for the seller.
            case SUSPENDED, ARCHIVED -> EnumSet.noneOf(ProductStatus.class);
        });
    }

    /** What the seller's own list shows by default — everything but the tombstones. */
    public static Set<ProductStatus> sellerVisible() {
        return Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(ARCHIVED)));
    }
}
