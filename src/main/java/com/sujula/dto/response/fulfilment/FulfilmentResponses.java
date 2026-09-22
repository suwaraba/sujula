package com.sujula.dto.response.fulfilment;

import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.VendorOrderStatus;

/**
 * What a seller gets back while working an order.
 *
 * <p>Every one of these says what the order now is, not merely that the call
 * worked. A seller packing six parcels on a phone with one bar of signal needs
 * to be able to reconcile the screen against the shelf without a second request.
 */
public final class FulfilmentResponses {

    private FulfilmentResponses() {}

    /** Accepted: the seller has said they will fulfil it. */
    public record Accepted(Long vendorOrderId, String orderNumber, VendorOrderStatus status,
                           LocalDateTime acceptedAt, String message) {}

    /**
     * Rejected, with the refund that was asked for.
     *
     * <p>Asked for, not paid. The refund reference is here so the seller can
     * quote it and so the response cannot be mistaken for money having moved —
     * an administrator decides that, and on a marketplace where a parcel may
     * already be halfway to another country an automatic refund is how you lose
     * both the goods and the money.
     */
    public record Rejected(Long vendorOrderId, String orderNumber, VendorOrderStatus status,
                           LocalDateTime cancelledAt, String reason,
                           String refundReference, boolean orderFullyCancelled,
                           int itemsReturnedToStock, String message) {}

    /**
     * Packed, with the code that releases it.
     *
     * <p>The code is returned here as well as from its own endpoint because this
     * is the moment the seller is holding the parcel; making them make a second
     * call to find out what to tell the driver is how a code gets written on the
     * box.
     */
    public record Ready(Long vendorOrderId, String orderNumber, VendorOrderStatus status,
                        LocalDateTime readyAt, ReleaseCode releaseCode, String message) {}

    /**
     * The code a driver must present to take the parcel.
     *
     * <p>Shown to the seller and to nobody else. It is not logged, it is not in
     * any list response, and the endpoint that serves it sets {@code no-store} —
     * a code cached by a proxy or sitting in a browser's back-forward cache is a
     * code that releases somebody else's goods.
     */
    public record ReleaseCode(String code, LocalDateTime issuedAt, LocalDateTime expiresAt,
                              int timesIssued, boolean reissued, String message) {}

    /**
     * One handset bound to one line.
     *
     * <p>Reports the line's whole state afterwards rather than just the handset
     * added, because the question a seller actually has is whether the line is
     * finished — three of three bound, or two with one still to scan.
     */
    public record ImeiAssigned(Long vendorOrderId, Long lineId, String imei,
                               int boundToLine, int requiredForLine, boolean lineComplete,
                               List<String> imeis, boolean orderReadyToPack, String message) {}

    /**
     * Where a parcel is going, as much of it as packing needs.
     *
     * <p>This is the whole of the buyer's personal information that crosses into
     * a seller's hands, and it is drawn from the <em>delivery</em> context rather
     * than the payer's (C1): the person who paid may be in Madrid and is not the
     * person the parcel is addressed to.
     *
     * <p>No street line, and that is deliberate rather than an omission. The
     * platform routes the parcel and the driver resolves the address from the
     * label's QR, so printing it here would hand every seller a home address
     * they have no delivery to make to. What a seller needs is the name to write
     * on the box, the town so they know whether it is crossing a border, and a
     * few digits of the phone to confirm they have the right parcel.
     */
    public record Shipping(String recipientName, String town, String country,
                           boolean international, String deliveryMode,
                           String pickupPointName, String phoneHint) {}
}
