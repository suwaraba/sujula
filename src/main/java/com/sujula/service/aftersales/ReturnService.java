package com.sujula.service.aftersales;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.model.constant.ReturnStatus;

/**
 * Goods going back, and the argument about whether they should.
 *
 * <p><strong>A return is not a refund.</strong> A refund is an instruction to a
 * payment provider; this is a parcel travelling the other way, and on this route
 * that takes days, costs money, and frequently should not happen at all —
 * carriage from Serrekunda to Banjul can exceed what is being returned. So the
 * ordinary settlement here is money offered with the goods staying where they
 * are, which is a state of this flow rather than a smaller refund somebody
 * typed.
 *
 * <p><strong>One seller's slice at a time (C3).</strong> Every method here takes
 * a vendor order or a return against one. There is no operation that returns "an
 * order", because an order is several sellers who each decide for themselves and
 * are each paid separately.
 *
 * <p>Both sides use the same surface and see different things on it. The reads
 * answer "is this waiting on me", because a buyer in Madrid and a seller in
 * Banjul are eight time zones apart and each needs to know whether the ball is
 * theirs without reading a status machine.
 */
public interface ReturnService {

    /** The buyer opens one, against one seller's slice and named items. */
    AfterSalesResponses.ReturnDetail open(Long userId, AfterSalesRequests.OpenReturn request);

    /**
     * Everything the caller can see, from whichever side they stand on.
     *
     * <p>One method rather than a buyer's and a seller's, because the row is the
     * same row. What differs is which of the two ownership tests finds it, and
     * that is decided from the caller rather than from a parameter they could
     * change.
     */
    PagedResponse<AfterSalesResponses.ReturnSummary> list(Long userId, ReturnStatus status,
                                                          Pageable pageable);

    /** One return, readable by either party and nobody else. */
    AfterSalesResponses.ReturnDetail detail(Long userId, Long returnId);

    // ── The seller's answers ─────────────────────────────────────────────────

    /** Yes, send it back. */
    AfterSalesResponses.ReturnDetail approve(Long userId, Long returnId,
                                             AfterSalesRequests.ApproveReturn request);

    /** No, and here is why — which the buyer can escalate if they disagree. */
    AfterSalesResponses.ReturnDetail reject(Long userId, Long returnId,
                                            AfterSalesRequests.RejectReturn request);

    /**
     * Keep it, and have some of the money back.
     *
     * <p>The sensible answer to most of what comes through here and the reason
     * this endpoint exists at all. Offering is not settling: the buyer has to
     * take it, and until they do the full claim is what stands.
     */
    AfterSalesResponses.ReturnDetail offerPartialRefund(
            Long userId, Long returnId, AfterSalesRequests.OfferPartialRefund request);

    /** The seller has the goods back. */
    AfterSalesResponses.ReturnDetail markReceived(Long userId, Long returnId,
                                                  AfterSalesRequests.ReturnReceived request);

    // ── The buyer's answers ──────────────────────────────────────────────────

    /** Taking the offer, which fixes what is owed at that moment. */
    AfterSalesResponses.ReturnDetail acceptOffer(Long userId, Long returnId);

    /**
     * Handing it to somebody impartial, which freezes the seller's money.
     *
     * <p>Returns the return rather than the dispute, because the buyer asked
     * about their return and the dispute is what happened to it. The dispute's
     * own reference is on the response.
     */
    AfterSalesResponses.ReturnDetail escalate(Long userId, Long returnId,
                                              AfterSalesRequests.EscalateReturn request);
}
