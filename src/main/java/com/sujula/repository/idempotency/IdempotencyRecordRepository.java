package com.sujula.repository.idempotency;

import com.sujula.model.idempotency.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    /**
     * The answer already given for this caller's key, if it is still replayable.
     *
     * <p>Scope and key together, never key alone: keys are chosen by clients, so
     * two shoppers will pick the same one, and a lookup by key alone would hand
     * one of them the other's response.
     */
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.scope = :scope AND r.idempotencyKey = :key "
         + "AND r.expiresAt > CURRENT_TIMESTAMP")
    Optional<IdempotencyRecord> findLive(@Param("scope") String scope, @Param("key") String key);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
