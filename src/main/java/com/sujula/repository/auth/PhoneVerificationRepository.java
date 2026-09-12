package com.sujula.repository.auth;

import com.sujula.model.auth.PhoneVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    /**
     * The challenge a confirm attempt should be checked against: this user's most
     * recent unconfirmed one. Requesting a new code supersedes the old, so only
     * the newest can be answered.
     */
    @Query("SELECT v FROM PhoneVerification v WHERE v.user.id = :userId AND v.confirmedAt IS NULL "
         + "ORDER BY v.createdAt DESC LIMIT 1")
    Optional<PhoneVerification> findLatestOutstanding(@Param("userId") Long userId);

    /** Rate limiting: how many codes this user has asked for recently. */
    @Query("SELECT COUNT(v) FROM PhoneVerification v WHERE v.user.id = :userId AND v.createdAt > :since")
    long countRequestedSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Modifying
    @Query("DELETE FROM PhoneVerification v WHERE v.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
