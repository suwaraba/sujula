package com.sujula.service.aftersales;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.aftersales.AfterSalesResponses;

/**
 * What happens to a review after it is written.
 *
 * <p>Three different people with three different powers, and keeping them apart
 * is the whole of this interface. The author may change or withdraw their own
 * words, for a while. The seller may answer, once. Anybody may report, and
 * reporting takes nothing down.
 *
 * <p>The last of those is the one that matters. A marketplace where a one-star
 * review disappears because the seller objected has no reviews worth reading,
 * and the sellers who lose most by that are the honest ones — their five stars
 * stop meaning anything the moment everybody's are five stars.
 */
public interface ReviewModerationService {

    /**
     * The author changing their mind, inside the window.
     *
     * <p>A window rather than forever. A review that can be rewritten a year
     * later is one a seller can negotiate over — "change it and I will refund
     * you" — and the rating the next buyer reads stops being about the phone.
     */
    AfterSalesResponses.ReviewView edit(Long userId, Long reviewId,
                                        AfterSalesRequests.EditReview request);

    /** The author withdrawing it, inside the same window. */
    AfterSalesResponses.ReviewDeleted delete(Long userId, Long reviewId);

    /**
     * The seller's answer. One per review.
     *
     * <p>One, and no edits. A seller who could reply repeatedly would argue
     * under a bad review until the argument was longer than the review, and the
     * person trying to decide whether to buy would be reading a quarrel.
     */
    AfterSalesResponses.ReviewView reply(Long userId, Long reviewId,
                                         AfterSalesRequests.ReplyToReview request);

    /** Anybody flagging it for a person to read. Nothing comes down on its own. */
    AfterSalesResponses.ReviewReported report(Long userId, Long reviewId,
                                              AfterSalesRequests.ReportReview request);
}
