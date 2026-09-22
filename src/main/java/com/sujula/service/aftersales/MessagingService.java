package com.sujula.service.aftersales;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.aftersales.AfterSalesResponses;

/**
 * A buyer and a seller, talking about one order or one product.
 *
 * <p><strong>Never about nothing.</strong> Every thread hangs off an order or a
 * product, which is what stops this being a messaging service: there is no way
 * to open a channel to a stranger, because there is no request shape that
 * expresses one. The only people who can write to a seller are somebody who
 * bought from them and somebody looking at something they are selling.
 *
 * <p><strong>Contact details are taken out.</strong> Not to control what people
 * say, but because a sale arranged privately has none of the protection the
 * platform exists to give: no escrow, so money sent is gone; no custody chain,
 * so nobody can prove what arrived; no dispute and no refund. The buyer on this
 * route is usually on another continent from the goods, which makes them exactly
 * the person least able to walk in and complain. The message still arrives, with
 * a line saying what was removed and why.
 */
public interface MessagingService {

    /** Everything the caller is part of, most recent first. */
    PagedResponse<AfterSalesResponses.ThreadSummary> threads(Long userId, Pageable pageable);

    /**
     * Opening one, or landing back in the one that already exists.
     *
     * <p>A buyer who asks about the same order twice belongs in the conversation
     * they were already having rather than in a second one the seller has to
     * notice.
     */
    AfterSalesResponses.ThreadDetail openThread(Long userId,
                                                AfterSalesRequests.OpenThread request);

    /**
     * One thread, with its messages — and marked read for whoever opened it.
     *
     * <p>Reading is a write here, which is why this is not a read-only method.
     * The alternative is a separate "mark read" call that clients forget to
     * make, and a badge that never clears.
     */
    AfterSalesResponses.ThreadDetail thread(Long userId, Long threadId, Pageable pageable);

    /** Saying something, after the filter has been over it. */
    AfterSalesResponses.MessageSent send(Long userId, Long threadId,
                                         AfterSalesRequests.SendMessage request);
}
