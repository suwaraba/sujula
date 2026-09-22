package com.sujula.repository.auth;

import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.SessionRevocationReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    /**
     * The live session holding this refresh token. Fetches the user in the same
     * query because every caller needs it immediately and a lazy load here would
     * be a second round trip on the hot refresh path.
     */
    @Query("SELECT s FROM UserSession s JOIN FETCH s.user WHERE s.refreshTokenHash = :hash")
    Optional<UserSession> findByRefreshTokenHash(@Param("hash") String hash);

    /**
     * The session a rotated-away token used to belong to.
     *
     * <p>A hit here is a replay: the legitimate client has already moved on to a
     * newer token, so whoever presented this one is not the legitimate client.
     */
    @Query("SELECT s FROM UserSession s JOIN FETCH s.user WHERE s.previousTokenHash = :hash")
    Optional<UserSession> findByPreviousTokenHash(@Param("hash") String hash);

    /**
     * The account behind a live session, in one indexed query.
     *
     * <p>This is what every authenticated request runs, and it is deliberately
     * one query rather than two. The filter has to load the {@code User} anyway —
     * the rest of the application reads it straight off the principal — so
     * checking the session in the same statement makes revocation take effect on
     * the very next request instead of whenever the access token happens to
     * expire. Remote sign-out that waits ten minutes is not remote sign-out.
     *
     * <p>The account checks are here too: a blocked or disabled user stops being
     * able to call anything immediately, without a separate lookup.
     */
    @Query("SELECT s.user FROM UserSession s WHERE s.id = :sessionId AND s.user.id = :userId "
         + "AND s.revokedAt IS NULL AND s.expiresAt > CURRENT_TIMESTAMP "
         + "AND s.user.enabled = true AND s.user.blocked = false")
    Optional<com.sujula.model.user.User> findAuthenticatedUser(@Param("sessionId") Long sessionId,
                                                               @Param("userId") Long userId);

    /** Oldest-first, for trimming a user back to the session cap. */
    @Query("SELECT s FROM UserSession s WHERE s.user.id = :userId AND s.revokedAt IS NULL "
         + "AND s.expiresAt > CURRENT_TIMESTAMP ORDER BY s.lastSeenAt ASC")
    List<UserSession> findActiveOldestFirst(@Param("userId") Long userId);

    /** One session, but only if it belongs to this user — ownership is in the query. */
    Optional<UserSession> findByIdAndUserId(Long id, Long userId);

    /** The device list, newest activity first. */
    List<UserSession> findByUserIdOrderByLastSeenAtDesc(Long userId);

    @Query("SELECT s FROM UserSession s WHERE s.user.id = :userId AND s.revokedAt IS NULL "
         + "AND s.expiresAt > CURRENT_TIMESTAMP ORDER BY s.lastSeenAt DESC")
    List<UserSession> findActiveByUserId(@Param("userId") Long userId);

    long countByUserIdAndRevokedAtIsNull(Long userId);

    /**
     * Ends every session for a user in one statement.
     *
     * <p>A bulk update rather than a loop: sign-out-everywhere and a password
     * change both need this to be atomic and cheap, and loading fifty sessions to
     * set two fields on each is neither. {@code excludeId} keeps the session that
     * triggered it alive, which is what a password change should do — the user
     * who just changed it stays signed in where they are.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.revokedAt = :now, s.revokedReason = :reason "
         + "WHERE s.user.id = :userId AND s.revokedAt IS NULL AND (:excludeId IS NULL OR s.id <> :excludeId)")
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("reason") SessionRevocationReason reason,
                         @Param("now") LocalDateTime now,
                         @Param("excludeId") Long excludeId);

    /** Housekeeping: rows whose refresh window closed long ago are of no further use. */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
