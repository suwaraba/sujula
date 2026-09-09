package com.sujula.service;

import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.security.LoginAttemptProperties;
import com.sujula.service.security.LoginAttemptTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How much rope each role gets, and when the lock actually falls. */
class LoginAttemptTrackerTest {

    private LoginAttemptProperties properties;
    private LoginAttemptTracker tracker;
    private User user;

    @BeforeEach
    void setUp() {
        UserRepository userRepository = mock(UserRepository.class);
        properties = new LoginAttemptProperties();

        user = new User();
        user.setId(1L);
        user.setRole(UserRole.CUSTOMER);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        tracker = new LoginAttemptTracker(userRepository, properties);
    }

    @Test
    void anAdminGetsFiveAttempts() {
        user.setRole(UserRole.ADMIN);

        for (int attempt = 1; attempt < 5; attempt++) {
            assertNull(tracker.recordFailure(1L), "attempt " + attempt + " must not lock the account");
        }
        assertNotNull(tracker.recordFailure(1L), "the fifth failure locks it");
        assertTrue(user.isLockedOut());
        assertEquals(5, user.getFailedLoginAttempts());
    }

    @Test
    void theOtherStaffRolesGetFifteen() {
        for (UserRole role : new UserRole[]{UserRole.VENDOR, UserRole.DELIVERY, UserRole.PICKUP_OPERATOR}) {
            user.setRole(role);
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);

            for (int attempt = 1; attempt < 15; attempt++) {
                assertNull(tracker.recordFailure(1L), role + " locked early, on attempt " + attempt);
            }
            assertNotNull(tracker.recordFailure(1L), role + " should lock on the fifteenth failure");
        }
    }

    @Test
    void aShopperIsNeverLockedOut() {
        for (int attempt = 0; attempt < 50; attempt++) {
            assertNull(tracker.recordFailure(1L));
        }
        assertFalse(user.isLockedOut(), "locking a customer out would itself be an attack on them");
        assertEquals(50, user.getFailedLoginAttempts(), "the failures are still counted for ops");
    }

    @Test
    void aLockedAccountIsNotPushedFurtherOutByMoreGuessing() {
        user.setRole(UserRole.ADMIN);
        for (int attempt = 0; attempt < 5; attempt++) {
            tracker.recordFailure(1L);
        }
        LocalDateTime lockedUntil = user.getLockedUntil();

        for (int attempt = 0; attempt < 20; attempt++) {
            assertEquals(lockedUntil, tracker.recordFailure(1L));
        }
        assertEquals(lockedUntil, user.getLockedUntil(),
                "otherwise anyone knowing the address could keep the owner out for good");
        assertEquals(5, user.getFailedLoginAttempts());
    }

    @Test
    void theCountStartsOverOnceALockHasExpired() {
        user.setRole(UserRole.ADMIN);
        user.setFailedLoginAttempts(5);
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));

        assertNull(tracker.recordFailure(1L), "an expired lock gives a fresh budget");
        assertEquals(1, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void aSuccessfulSignInClearsEverything() {
        user.setRole(UserRole.ADMIN);
        user.setFailedLoginAttempts(3);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));

        tracker.recordSuccess(1L);

        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    void theBudgetAndTheDurationAreConfigurable() {
        properties.getMaxAttempts().put(UserRole.CUSTOMER, 3);
        properties.setLockoutDuration(Duration.ofHours(2));

        tracker.recordFailure(1L);
        tracker.recordFailure(1L);
        LocalDateTime lockedUntil = tracker.recordFailure(1L);

        assertNotNull(lockedUntil);
        assertTrue(lockedUntil.isAfter(LocalDateTime.now().plusMinutes(110)));
    }

    @Test
    void anAdminUnlockLiftsItImmediately() {
        user.setFailedLoginAttempts(9);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(15));

        tracker.clear(user);

        assertFalse(user.isLockedOut());
        assertEquals(0, user.getFailedLoginAttempts());
    }
}
