package com.sujula.model.constant;

/**
 * Which link of the custody chain a code covers.
 *
 * <p>Every transfer of a parcel is a verified event with proof, and for most of
 * them the proof is a short code the receiving party reads out. The type says
 * which handover it authorises, so a code that opens one link cannot be
 * presented at another.
 */
public enum HandoverCodeType {

    /**
     * The seller releasing a packed slice to whoever collects it.
     *
     * <p>The only one that hangs off a vendor order rather than a delivery, and
     * it has to: a seller packs one parcel for the whole slice, and a driver
     * collecting it presents one code rather than one per line.
     */
    VENDOR_RELEASE,

    /**
     * The recipient releasing the parcel to themselves, at their door or a counter.
     *
     * <p>The last link, and the one C5 is about. The person who has to present
     * this may have no account, no app and no email — she is the sister in
     * Serrekunda, and she did not sign up for anything. So the code does not go
     * to her: it goes to the buyer who paid, by email, and they pass it on the
     * way somebody passes on a Western Union reference. The recipient needs only
     * to be told a number by the person who sent them the parcel.
     */
    RECIPIENT_RELEASE,

    /**
     * One driver handing a parcel to another.
     *
     * <p>Both of them present something: the chain records a link that two
     * people attest to rather than one somebody claimed.
     */
    DRIVER_TO_DRIVER,

    VENDOR_TO_DRIVER,       // Vendor -> Driver pickup, per delivery
    VENDOR_TO_PICKUP,       // Vendor -> Pickup point deposit
    DRIVER_TO_PICKUP,       // Driver -> Pickup point (return / hub transfer)
    PICKUP_TO_DRIVER,       // Pickup point -> Driver (last-mile assignment)
    PICKUP_TO_CUSTOMER,     // Pickup point -> Customer self-collection
    DRIVER_TO_CUSTOMER;     // Driver -> Customer door delivery

    /** Whether this code belongs to a vendor order rather than a single parcel. */
    public boolean isVendorScoped() {
        return this == VENDOR_RELEASE;
    }

    /**
     * Whether the person presenting this may have no account at all.
     *
     * <p>True for exactly one type, and it changes how the code is delivered:
     * everybody else reads theirs in an app they are signed into, and the
     * recipient is told hers by the person who paid.
     */
    public boolean isForSomebodyWithoutAnAccount() {
        return this == RECIPIENT_RELEASE;
    }
}
