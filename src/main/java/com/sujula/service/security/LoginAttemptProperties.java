package com.sujula.service.security;

import com.sujula.model.constant.UserRole;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * What happens as failed sign-ins pile up on one account.
 *
 * <p>Three steps, counted in consecutive failures: warn the owner, offer them a
 * way back in, then stop accepting attempts. Everyone gets the same ladder;
 * admins climb it faster, because an admin account moves money, approves
 * vendors and reads every customer's details, and is worth far more to an
 * attacker than the seven guesses a shopper's account is allowed.
 *
 * <p>A step set to zero or left unset never fires, so a role can be given a
 * lockout with no emails, emails with no lockout, or nothing at all.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.security.login")
public class LoginAttemptProperties {

    /** Applies to every role without an entry in {@link #roles}. */
    private LoginPolicy defaultPolicy = LoginPolicy.of(3, 5, 7);

    /** Per-role overrides. */
    private Map<UserRole, LoginPolicy> roles = defaultRoles();

    /**
     * How long a lockout lasts. It expires on its own so support is not in the
     * loop for a mistyped password — and so an attacker who learns an admin's
     * address cannot keep them out indefinitely.
     */
    private Duration lockoutDuration = Duration.ofMinutes(15);

    public LoginPolicy policyFor(UserRole role) {
        if (role == null) {
            return defaultPolicy;
        }
        return roles.getOrDefault(role, defaultPolicy);
    }

    private static Map<UserRole, LoginPolicy> defaultRoles() {
        Map<UserRole, LoginPolicy> policies = new EnumMap<>(UserRole.class);
        // No warning step: an admin who has failed three times is already at the
        // point where the others only get told to expect trouble.
        policies.put(UserRole.ADMIN, LoginPolicy.of(0, 3, 4));
        return policies;
    }

    /**
     * The ladder for one kind of account, in consecutive failed attempts.
     *
     * @param warnAt       failure that triggers the "someone is trying to sign
     *                     in" notice, which points at password recovery but
     *                     carries no token — at this point there is no reason to
     *                     believe the person failing is the owner
     * @param resetEmailAt failure that issues a real password-reset link, for an
     *                     owner who has evidently forgotten theirs
     * @param lockAt       failure that stops the account accepting sign-ins
     */
    @Getter
    @Setter
    public static class LoginPolicy {

        private int warnAt;
        private int resetEmailAt;
        private int lockAt;

        public static LoginPolicy of(int warnAt, int resetEmailAt, int lockAt) {
            LoginPolicy policy = new LoginPolicy();
            policy.warnAt = warnAt;
            policy.resetEmailAt = resetEmailAt;
            policy.lockAt = lockAt;
            return policy;
        }

        /**
         * Steps fire on the exact attempt that reaches them, never after, so one
         * counting cycle can never send the same mail twice — an attacker must
         * not be able to turn a login form into a mail cannon aimed at a user.
         */
        public boolean warnsAt(int attempts) {
            return warnAt > 0 && attempts == warnAt;
        }

        public boolean sendsResetAt(int attempts) {
            return resetEmailAt > 0 && attempts == resetEmailAt;
        }

        public boolean locksAt(int attempts) {
            return lockAt > 0 && attempts >= lockAt;
        }

        /** Attempts left before the lock falls; zero when this role never locks. */
        public int remainingBefore(int attempts) {
            return lockAt > 0 ? Math.max(0, lockAt - attempts) : 0;
        }
    }
}
