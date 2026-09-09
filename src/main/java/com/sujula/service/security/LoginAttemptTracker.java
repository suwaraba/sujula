package com.sujula.service.security;

import com.sujula.model.user.User;
import com.sujula.repository.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Counts failed sign-ins and locks an account that has spent its budget.
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

    private final UserRepository userRepository;
    private final LoginAttemptProperties properties;

    public LoginAttemptTracker(UserRepository userRepository, LoginAttemptProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    /**
     * Records one wrong password and locks the account if that spends its budget.
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
        user.setFailedLoginAttempts(attempts);
        user.setLastFailedLoginAt(LocalDateTime.now());
        user.setLockedUntil(null);

        LocalDateTime lockedUntil = properties.maxAttemptsFor(user.getRole())
                .filter(limit -> attempts >= limit)
                .map(limit -> LocalDateTime.now().plus(properties.getLockoutDuration()))
                .orElse(null);

        if (lockedUntil != null) {
            user.setLockedUntil(lockedUntil);
            log.warn("[Login] {} account {} locked until {} after {} failed attempts",
                    user.getRole(), user.getId(), lockedUntil, attempts);
        }
        userRepository.save(user);
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

    /** True when a lock was set but has since run out, so the count starts over. */
    private boolean expiredLock(User user) {
        return user.getLockedUntil() != null && !user.isLockedOut();
    }
}
