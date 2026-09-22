package com.sujula.repository.auth;

import com.sujula.model.auth.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    /**
     * The codes still available to this user.
     *
     * <p>Returns rows rather than matching in the query because the codes are
     * BCrypt hashed: BCrypt embeds a per-code salt, so the same code hashes
     * differently every time and there is nothing to look up by. Each candidate
     * has to be checked with the encoder. The set is at most ten rows.
     */
    @Query("SELECT c FROM MfaRecoveryCode c WHERE c.user.id = :userId AND c.usedAt IS NULL")
    List<MfaRecoveryCode> findUnusedByUserId(@Param("userId") Long userId);

    long countByUserIdAndUsedAtIsNull(Long userId);

    /** Regenerating replaces the whole set; a half-rotated batch would be worse than none. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM MfaRecoveryCode c WHERE c.user.id = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
