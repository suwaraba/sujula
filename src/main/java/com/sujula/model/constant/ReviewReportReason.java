package com.sujula.model.constant;

/**
 * Why somebody flagged a review.
 *
 * <p>A report is not a deletion. Sellers report reviews they dislike, and a
 * marketplace that removed a one-star review because the seller objected would
 * have no reviews worth reading. These reasons exist so a moderator can tell a
 * rule being broken from a seller being unhappy.
 */
public enum ReviewReportReason {

    /** Abuse, slurs, threats. */
    ABUSIVE,

    /** An advert, a link, or somebody else's shop. */
    SPAM,

    /** Somebody's phone number, address or full name in a public review. */
    PERSONAL_INFORMATION,

    /** About a different product entirely. */
    WRONG_PRODUCT,

    /**
     * Claimed to be untrue.
     *
     * <p>Deliberately last and deliberately narrow. This is the one a seller
     * reaches for, and it is the one that needs a person to read the order, the
     * custody chain and the review together — which is why reporting never
     * hides anything on its own.
     */
    FALSE_CLAIM
}
