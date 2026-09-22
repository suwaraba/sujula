package com.sujula.repository.admin;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.admin.Sanction;

@Repository
public interface SanctionRepository extends JpaRepository<Sanction, Long> {

    List<Sanction> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<Sanction> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * The sanctions biting on an account right now.
     *
     * <p>Expiry is in the query rather than checked afterwards, so a suspension
     * is over the moment its date passes whether or not any sweep job ran. An
     * outage must not leave somebody locked out for an extra week.
     */
    @Query("SELECT s FROM Sanction s WHERE s.user.id = :userId AND s.liftedAt IS NULL "
         + "AND (s.expiresAt IS NULL OR s.expiresAt > :now) ORDER BY s.createdAt DESC")
    List<Sanction> findActive(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * How many times this account has been sanctioned before.
     *
     * <p>Counted including the lapsed and the lifted, because a pattern is what
     * justifies the next step being heavier — and a suspension that ended is
     * still a suspension that happened.
     */
    @Query("SELECT COUNT(s) FROM Sanction s WHERE s.user.id = :userId")
    long countHistory(@Param("userId") Long userId);

    /**
     * Accounts locked out whose lock has now lapsed.
     *
     * <p>Read by the job that puts {@code users.enabled} back. The flag is only
     * ever a cache of this query's answer — nothing depends on the job having
     * run, because every gate re-derives from the sanctions themselves.
     */
    @Query("SELECT DISTINCT s.user.id FROM Sanction s WHERE s.liftedAt IS NULL "
         + "AND s.expiresAt IS NOT NULL AND s.expiresAt <= :now "
         + "AND s.type IN (com.sujula.model.constant.SanctionType.SUSPENSION)")
    List<Long> findUsersWithLapsedLocks(@Param("now") LocalDateTime now);
}
