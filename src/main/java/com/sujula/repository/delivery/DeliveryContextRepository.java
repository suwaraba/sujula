package com.sujula.repository.delivery;

import com.sujula.model.delivery.DeliveryContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface DeliveryContextRepository extends JpaRepository<DeliveryContext, String> {

    /**
     * A context that is still usable, by its id alone.
     *
     * <p>The expiry is in the query rather than checked afterwards so an expired
     * context is indistinguishable from one that never existed. A caller probing
     * ids learns nothing from the difference, and no caller can forget the check.
     */
    @Query("SELECT c FROM DeliveryContext c WHERE c.id = :id AND c.expiresAt > CURRENT_TIMESTAMP")
    Optional<DeliveryContext> findLive(@Param("id") String id);

    /** Housekeeping: expired contexts are bearer credentials nobody should still hold. */
    @Modifying
    @Query("DELETE FROM DeliveryContext c WHERE c.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
