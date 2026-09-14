package com.sujula.service.aftersales;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.model.aftersales.Dispute;
import com.sujula.model.aftersales.ReturnRequest;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;

/**
 * Two people who cannot agree, and the money held still while somebody decides.
 *
 * <p>This is the only writer of a dispute freeze, and that is the property worth
 * stating: opening a dispute marks the sub-order frozen, {@code MoneyLedger}
 * refuses to release escrow on a frozen slice, and where the money had already
 * been released a hold entry takes it back out of the available balance. Three
 * things, one writer, so a freeze cannot be half-applied.
 *
 * <p><strong>One seller's slice, never the payment (C3).</strong> A buyer
 * disputing the phone from Kombo Electronics has no quarrel with the charger
 * Teranga Mobile sent on the same card, and Teranga's payout runs on time.
 */
public interface DisputeService {

    /** The buyer raises one directly. */
    AfterSalesResponses.DisputeDetail open(Long userId, AfterSalesRequests.OpenDispute request);

    PagedResponse<AfterSalesResponses.DisputeSummary> list(Long userId, DisputeStatus status,
                                                           Pageable pageable);

    AfterSalesResponses.DisputeDetail detail(Long userId, Long disputeId);

    /** Either side adds to the case file. */
    AfterSalesResponses.DisputeDetail addMessage(Long userId, Long disputeId,
                                                 AfterSalesRequests.DisputeMessage request);

    /** Either side puts in a file, with a line saying what it shows. */
    AfterSalesResponses.DisputeDetail addEvidence(Long userId, Long disputeId,
                                                  AfterSalesRequests.DisputeEvidence request);

    /**
     * The person who raised it takes it back, which lifts the freeze.
     *
     * <p>Only the raiser, and only them. A seller who could close a dispute
     * against themselves would be a seller who is never disputed.
     */
    AfterSalesResponses.DisputeDetail withdraw(Long userId, Long disputeId,
                                               AfterSalesRequests.WithdrawDispute request);

    /**
     * A return becoming a dispute, called from the return flow.
     *
     * <p>Returns the entity rather than a view, because the caller is the return
     * service linking the two rows together — and because the freeze has to
     * happen here, in the one place that knows how to do all three parts of it.
     */
    Dispute escalateFromReturn(Long userId, ReturnRequest returnRequest, String description,
                               DisputeReason reason);
}
