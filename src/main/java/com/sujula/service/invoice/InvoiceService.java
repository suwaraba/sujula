package com.sujula.service.invoice;

import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.model.order.Order;

/**
 * Issues the buyer's invoice as a PDF behind a signed, expiring link.
 *
 * <p>The document is rendered on demand and never stored. An invoice states what
 * somebody was charged, and that is a fact about the order — so re-deriving it
 * from the order is correct, provided nothing in the derivation can drift. It
 * cannot: every converted figure on the order carries the rate it was converted
 * at (C2), so a PDF produced today and one produced next year read identically.
 */
public interface InvoiceService {

    /**
     * A short-lived signed URL for {@code order}'s invoice.
     *
     * <p>The link is the capability. Ownership is proven once, by the caller, on
     * the endpoint that mints it — the download endpoint itself is open, because
     * the recipient of a forwarded invoice may be the person who received the
     * parcel and has no account (C5).
     */
    BuyerOrderResponses.DocumentLink link(Order order);

    /**
     * Renders the invoice a signed token names.
     *
     * @throws com.sujula.exceptions.BadRequestException if the token is
     *         malformed, has been tampered with, or has expired
     */
    RenderedInvoice render(String token);

    /** A rendered document, ready to stream. */
    record RenderedInvoice(byte[] content, String filename, String contentType) {}
}
