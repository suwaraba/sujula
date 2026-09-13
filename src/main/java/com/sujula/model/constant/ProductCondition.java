package com.sujula.model.constant;

/**
 * What state the goods are in.
 *
 * <p>A first-class filter rather than a line in the description, because on a
 * marketplace where a phone can cost a month's income the difference between new
 * and refurbished is most of the buying decision — and because a diaspora buyer
 * choosing a gift for someone at home cannot inspect it themselves. They are
 * relying entirely on what the listing says.
 */
public enum ProductCondition {

    /** Unused, sealed, as it left the manufacturer. */
    NEW,

    /**
     * Opened but unused — a returned item, a display unit.
     *
     * <p>Distinct from refurbished: nothing was repaired, it was simply no longer
     * sealed.
     */
    OPEN_BOX,

    /** Repaired or restored and tested, usually with a shorter warranty. */
    REFURBISHED,

    /** Owned and used before. */
    USED,

    /** Sold as not working, for parts or repair. */
    FOR_PARTS;

    /** Whether a buyer should expect manufacturer warranty terms. */
    public boolean isNew() {
        return this == NEW;
    }
}
