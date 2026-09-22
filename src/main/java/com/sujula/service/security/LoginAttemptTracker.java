package com.sujula.service.security;

import com.sujula.model.user.User;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.EmailService;
import com.sujula.service.security.LoginAttemptProperties.LoginPolicy;
import com.sujula.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Counts failed sign-ins and walks the account up the escalation ladder: warn
 * the owner, send them a way back in, then stop accepting attempts.
 *
 * <p>A separate bean with its own transactions, on purpose. A failed sign-in
 * ends in an exception, and an exception rolls its transaction back — so a
 * counter incremented in the same transaction as the failure would be undone by
 * the very failure it was counting, and an attacker would get unlimited
 * attempts. Every method here commits on its own.
 */
@Service
public class LoginAttemptTracker {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptTracker.class);

    /** Matches the window the password-reset flow uses. */
    private static final int RESET_TOKEN_HOURS = 24;

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final LoginAttemptProperties properties;

    public LoginAttemptTracker(UserRepository userRepository, EmailService emailService,
                               LoginAttemptProperties properties) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.properties = properties;
    }

    /**
     * Records one wrong password and applies whatever step that reaches.
     *
     * <p>An account already locked is left alone rather than pushed further out:
     * extending the lock on every attempt would let anyone who knows the address
     * keep the owner out for as long as they cared to keep guessing.
     *
     * @return when the lock now expires, or null if the account is not locked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LocalDateTime recordFailure(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        if (user.isLockedOut()) {
            return user.getLockedUntil();
        }

        int attempts = expiredLock(user) ? 1 : user.getFailedLoginAttempts() + 1;
        LoginPolicy policy = properties.policyFor(user.getRole());

        user.setFailedLoginAttempts(attempts);
        user.setLastFailedLoginAt(LocalDateTime.now());
        user.setLockedUntil(null);

        LocalDateTime lockedUntil = null;
        if (policy.locksAt(attempts)) {
            lockedUntil = LocalDateTime.now().plus(properties.getLockoutDuration());
            user.setLockedUntil(lockedUntil);
            log.warn("[Login] {} account {} locked until {} after {} failed attempts",
                    user.getRole(), user.getId(), lockedUntil, attempts);
        }

        // A reset link is minted here rather than through requestPasswordReset,
        // which would make this bean and UserServiceImpl depend on each other.
        if (policy.sendsResetAt(attempts)) {
            user.setPasswordResetToken(Utils.generateSecureToken());
            user.setPasswordResetTokenExpiry(LocalDateTime.now().plusHours(RESET_TOKEN_HOURS));
        }

        userRepository.save(user);

        // After the save: the count and the lock are what protect the account,
        // and a mail server having a bad day must not roll either of them back.
        if (policy.warnsAt(attempts)) {
            notifyOwner(user, attempts, policy);
        }
        if (policy.sendsResetAt(attempts)) {
            sendRecoveryLink(user, attempts);
        }
        return lockedUntil;
    }

    /** Clears the count after a sign-in that worked. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            if (user.getFailedLoginAttempts() == 0 && user.getLockedUntil() == null) {
                return;   // nothing to clear — the common case, so skip the write
            }
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        });
    }

    /** Lifts a lock early, for an admin unlocking someone who is waiting on support. */
    @Transactional
    public void clear(User user) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
    }

    /** "Someone is failing to sign in as you, and here is how to recover." No token. */
    private void notifyOwner(User user, int attempts, LoginPolicy policy) {
        try {
            emailService.sendFailedSignInWarningEmail(user.getEmail(), user.getFullName(),
                    attempts, policy.remainingBefore(attempts));
        } catch (Exception ex) {
            log.warn("[Login] Could not warn user {} about failed sign-ins: {}", user.getId(), ex.getMessage());
        }
    }

    private void sendRecoveryLink(User user, int attempts) {
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), user.getPasswordResetToken());
            log.info("[Login] Password-reset link sent to user {} after {} failed attempts", user.getId(), attempts);
        } catch (Exception ex) {
            log.warn("[Login] Could not send a reset link to user {}: {}", user.getId(), ex.getMessage());
        }
    }

    /** True when a lock was set but has since run out, so the count starts over. */
    private boolean expiredLock(User user) {
        return user.getLockedUntil() != null && !user.isLockedOut();
    }
}
