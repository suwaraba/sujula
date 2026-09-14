package com.sujula.controller;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;

/**
 * Names the member of staff behind an administrative request, and says whether
 * they may decide anything.
 *
 * <p>Two roles reach {@code /admin} and they are not the same thing. Support
 * reads everything and answers people; an administrator moves money, changes
 * statuses and suspends accounts. The split is enforced here rather than by
 * remembering to annotate each method, because the annotation that gets
 * forgotten is on the one endpoint that mattered.
 *
 * <p>{@link #decider} is the call every write makes. It reads as what it does —
 * a method named for the thing it demands — so an endpoint that calls
 * {@link #staff} instead is visibly a read.
 */
@Component
public class StaffCaller {

    private final AuthenticatedCaller caller;

    public StaffCaller(AuthenticatedCaller caller) {
        this.caller = caller;
    }

    /**
     * Anybody who may see the administrative surface at all.
     *
     * <p>For reads: the queues, the dashboards, a user's profile, a custody
     * chain. Support lives here.
     */
    public User staff(Authentication authentication) {
        User user = caller.user(authentication);
        if (user.getRole() == null || !user.getRole().isStaff()) {
            // Not "forbidden, you are not an admin" — the admin surface does not
            // confirm its own shape to somebody who should not be on it.
            throw new AccessDeniedException("Authentication is required");
        }
        return user;
    }

    /**
     * Somebody who may make a decision, not only read one.
     *
     * <p>Every write on {@code /admin} goes through this. A support agent
     * calling one gets the same refusal as a stranger, which is deliberate:
     * "you may look but not touch" is a rule the client should already be
     * rendering, and repeating it in the error is how a message ends up telling
     * somebody what to try next.
     */
    public User decider(Authentication authentication) {
        User user = staff(authentication);
        if (!user.getRole().canDecide()) {
            throw new AccessDeniedException("Authentication is required");
        }
        return user;
    }

    /** The staff member's id, for the audit trail. */
    public Long staffId(Authentication authentication) {
        return staff(authentication).getId();
    }

    /** Whether the caller is an administrator rather than support. */
    public boolean canDecide(Authentication authentication) {
        User user = caller.user(authentication);
        return user.getRole() != null && user.getRole().canDecide();
    }

    /** Whether a role is one of the two that reach this surface. */
    public static boolean isStaff(UserRole role) {
        return role != null && role.isStaff();
    }
}
