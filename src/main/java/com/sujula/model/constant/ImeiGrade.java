package com.sujula.model.constant;

/**
 * The condition of one used handset, as the trade grades them.
 *
 * <p>A finer scale than {@link ProductCondition} because it has to be. Most
 * phones sold here are second-hand, the buyer is frequently thousands of miles
 * away choosing a gift for someone at home, and "used" covers everything from a
 * phone opened once to one with a cracked back. That gap is most of the dispute
 * surface on this marketplace.
 */
public enum ImeiGrade {

    /** Sealed, never used. */
    NEW,

    /** Opened, unmarked, indistinguishable from new in hand. */
    A_GRADE,

    /** Light marks visible close up. Screen unmarked. */
    B_GRADE,

    /** Obvious wear, possibly a scratched screen. Works fully. */
    C_GRADE,

    /** Works, but with a named fault the listing must state. */
    FOR_PARTS
}
