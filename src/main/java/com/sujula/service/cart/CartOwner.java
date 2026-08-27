package com.sujula.service.cart;

import com.sujula.exceptions.BadRequestException;

import java.util.Objects;

/**
 * Identifies whose cart an operation targets — either a signed-in user or an
 * anonymous browser session.
 *
 * <p>Collapsing the two into one type removes the parallel {@code xxx} /
 * {@code xxxGuest} method pairs that previously had to be kept in lockstep by
 * hand, which is how the guest path drifted out of sync with the user path.
 *
 * <p><strong>Security:</strong> callers must build this from the authenticated
 * principal or from the guest cookie the server itself issued. A session id
 * taken straight off a client-supplied header is a bearer token for someone
 * else's cart.
 */
public final class CartOwner {

    private final Long userId;
    private final String sessionId;

    private CartOwner(Long userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
    }

    public static CartOwner user(Long userId) {
        if (userId == null) {
            throw new BadRequestException("Authenticated cart access requires a user id");
        }
        return new CartOwner(userId, null);
    }

    public static CartOwner guest(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BadRequestException("Guest cart access requires a session id");
        }
        if (sessionId.length() > 36) {
            throw new BadRequestException("Malformed guest session id");
        }
        return new CartOwner(null, sessionId.trim());
    }

    public boolean isGuest() {
        return userId == null;
    }

    public Long userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CartOwner other)) return false;
        return Objects.equals(userId, other.userId) && Objects.equals(sessionId, other.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, sessionId);
    }

    @Override
    public String toString() {
        return isGuest() ? "CartOwner[guest]" : "CartOwner[user=" + userId + "]";
    }
}
