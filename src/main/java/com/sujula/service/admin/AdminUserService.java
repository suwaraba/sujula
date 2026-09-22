package com.sujula.service.admin;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.admin.AdminUserRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminUserResponses;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;

/**
 * What an administrator may do to somebody else's account.
 *
 * <p>Every method takes the acting staff member as its first argument rather
 * than reading one from a context. That is on purpose and it is the shape the
 * whole admin surface uses: the audit row is written from that argument, so a
 * method that could not name who was acting could not be called at all.
 *
 * <p><strong>Nothing here sets {@code enabled} directly.</strong> Locking and
 * unlocking go through {@code SanctionRegistry}, which means every account that
 * cannot sign in has a row saying who decided that, why, and until when.
 * Deactivating and suspending are the same mechanism with different durations —
 * one indefinite, one with an end date — and calling them by different names in
 * the API rather than in the data is what keeps the record honest.
 */
public interface AdminUserService {

    /** The search. Every filter optional; the text match is narrow on purpose. */
    PagedResponse<AdminUserResponses.UserRow> search(User staff, String query, UserRole role,
                                                     String country, Boolean blocked,
                                                     Boolean lockedOut, Pageable pageable);

    /** One account with the shape of its history, its sanctions and its devices. */
    AdminUserResponses.UserDetail detail(User staff, Long userId);

    /**
     * Creates an actor by hand, for the cases self-registration cannot serve.
     *
     * <p>No password is accepted or returned: one is minted and the account is
     * sent a link to set its own, because a password an administrator chose is
     * one an administrator knows.
     */
    AdminUserResponses.UserCreated create(User staff, AdminUserRequests.CreateUser request);

    /** Editing somebody's profile on their behalf. Audited with the reason. */
    AdminUserResponses.UserDetail patch(User staff, Long userId,
                                        AdminUserRequests.PatchUser request);

    /** Lifts whatever is holding an account out. */
    AdminUserResponses.AccountChanged activate(User staff, Long userId,
                                               AdminUserRequests.Activate request);

    /** Shuts an account indefinitely, and ends its sessions. */
    AdminUserResponses.AccountChanged deactivate(User staff, Long userId,
                                                 AdminUserRequests.Deactivate request);

    /** Shuts an account for a stated number of days, after which it returns by itself. */
    AdminUserResponses.AccountChanged suspend(User staff, Long userId,
                                              AdminUserRequests.Suspend request);

    /**
     * Changes what kind of actor an account is.
     *
     * <p>Ends every session, because a role is carried in the access token: a
     * user promoted to administrator with a live token is still a customer to
     * every request until it expires, and one demoted from it is still an
     * administrator — which is the direction that matters.
     */
    AdminUserResponses.RoleChanged changeRole(User staff, Long userId,
                                              AdminUserRequests.ChangeRole request);

    /** Ends every session an account has open. */
    AdminUserResponses.SessionsEnded forceLogout(User staff, Long userId,
                                                 AdminUserRequests.ForceLogout request);

    /**
     * Clears somebody's second factor so they can enrol again.
     *
     * <p>Step-up on the administrator's own credentials first. This is the
     * endpoint an attacker who has reached an admin account wants most, because
     * it turns one account takeover into a takeover of anybody.
     */
    AdminUserResponses.MfaReset resetMfa(User staff, Long userId,
                                         AdminUserRequests.ResetMfa request);

    /**
     * Opens a short session as somebody else.
     *
     * <p>Time-boxed, flagged in the token so every client can show a banner, and
     * audited with the reason that was given. It cannot be refreshed: an
     * administrator who needs longer asks again, and two rows for an hour is the
     * record this endpoint exists to leave.
     */
    AdminUserResponses.ImpersonationOpened impersonate(User staff, Long userId,
                                                       AdminUserRequests.Impersonate request);
}
