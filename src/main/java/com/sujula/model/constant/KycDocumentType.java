package com.sujula.model.constant;

import java.util.EnumSet;
import java.util.Set;

/**
 * A kind of document a store must produce before it can trade.
 *
 * <p>What is required depends on who is applying. A market trader in Serrekunda
 * selling from a stall has a national ID and nothing else; a registered company
 * in Dakar has a registration certificate and a tax number. Demanding the
 * company's paperwork from the trader excludes most of the sellers this
 * marketplace exists for, so identity is the floor and the business documents
 * are required only from someone who claims to be a business.
 */
public enum KycDocumentType {

    /** Identity. One of these is always required. */
    NATIONAL_ID,
    PASSPORT,
    DRIVING_LICENCE,

    /** Required only of a store that gave a registration number. */
    BUSINESS_REGISTRATION,

    /** Required only of a store that gave a tax number. */
    TAX_CERTIFICATE,

    /** Ties the applicant to the pickup address a driver will be sent to. */
    PROOF_OF_ADDRESS,

    /** Ties the applicant to the account they want to be paid into. */
    BANK_STATEMENT;

    /** Any one of these satisfies the identity requirement. */
    public static Set<KycDocumentType> identity() {
        return EnumSet.of(NATIONAL_ID, PASSPORT, DRIVING_LICENCE);
    }

    public boolean isIdentity() {
        return identity().contains(this);
    }
}
