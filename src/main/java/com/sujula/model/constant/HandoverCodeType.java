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
}
