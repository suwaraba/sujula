package com.sujula.service.store;

import com.sujula.dto.request.store.StoreRequests;
import com.sujula.dto.response.store.StoreResponses;

/**
 * A seller's own store: opening it, describing it, proving who they are, saying
 * who else may work in it, and naming where the money goes.
 *
 * <p>Every method takes the caller's user id and resolves the store by id
 * <em>and</em> owner in one query. There is no method here that can be made to
 * reach another seller's store by changing an argument.
 *
 * <p><strong>Two locations, and neither answers for the other.</strong> A store
 * carries an address because a driver has to collect from it — that is a
 * delivery-side fact, and what it decides is distance, serviceability and
 * collection times. It carries a settlement currency because the seller has a
 * bank — a payment-side fact, and what it decides is what they list in and are
 * paid in. The first never sets the second: a Gambian trading from a warehouse
 * in Dakar settles in GMD, and a marketplace that reads the currency off the
 * address would have quietly re-denominated their business.
 */
public interface StoreService {

    /**
     * Opens a store, in {@code PENDING_KYC}.
     *
     * <p>Nothing can be sold yet. The state exists so the applicant's queue and
     * the reviewer's queue are different queues: this one is waiting on the
     * seller to send documents, not on anyone here to read them.
     */
    StoreResponses.Store create(Long userId, StoreRequests.CreateStore request);

    /** The caller's own store. Somebody else's is not found. */
    StoreResponses.Store get(Long userId, Long storeId);

    /**
     * Edits the store. Absent fields are left alone.
     *
     * <p>The slug never moves, whatever happens to the name: it is in links
     * buyers have saved and in search engines' indexes, and a rename that
     * silently 404s a storefront is a rename that costs a seller their traffic.
     */
    StoreResponses.Store update(Long userId, Long storeId, StoreRequests.UpdateStore request);

    // ── KYC ──────────────────────────────────────────────────────────────────

    /**
     * Submits identity and business documents.
     *
     * <p>A new upload of a type supersedes the previous one rather than replacing
     * it, so the sequence of attempts and the reasons they were refused survives.
     * Onboarding disputes are always about that sequence.
     */
    StoreResponses.KycState submitKyc(Long userId, Long storeId, StoreRequests.SubmitKyc request);

    /**
     * What has been accepted, what is missing, and why anything was refused.
     *
     * <p>Derived from the documents rather than stored beside them. A summary
     * kept alongside the rows it summarises drifts the first time a decision is
     * recorded through a path that forgets to update it, and the drift shows up
     * as a verified store being told it is not.
     */
    StoreResponses.KycState kycState(Long userId, Long storeId);

    // ── Staff ────────────────────────────────────────────────────────────────

    StoreResponses.StaffList staff(Long userId, Long storeId);

    /**
     * Invites somebody by email, who may not have an account here.
     *
     * <p>Keyed on the address rather than on a user, because the commonest
     * invitee is a relative or an assistant who has never used the platform. The
     * row waits for them to register.
     */
    StoreResponses.StaffMember invite(Long userId, Long storeId, StoreRequests.InviteStaff request);

    /** Replaces what a staff member may do. The owner cannot be rescoped here. */
    StoreResponses.StaffMember updateStaff(Long userId, Long storeId, Long staffUserId,
                                           StoreRequests.UpdateStaff request);

    /**
     * Revokes access. The row stays, marked, so a later question about who could
     * see what, and when, has something to read.
     */
    void removeStaff(Long userId, Long storeId, Long staffUserId);

    // ── Payout destination ───────────────────────────────────────────────────

    /**
     * Replaces where this store's money goes, after re-authenticating the caller.
     *
     * <p>Replaces rather than appends: a store that accumulates old destinations
     * is a store where a payout run has a choice to get wrong.
     *
     * <p>The account number is encrypted in the column and never comes back out
     * of this API — what is returned is the last four digits, which is enough to
     * recognise an account and useless for sending money to one.
     */
    StoreResponses.PayoutDestination putBankAccount(Long userId, Long storeId,
                                                    StoreRequests.PutBankAccount request);
}
