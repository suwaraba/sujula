package com.sujula.repository.delivery;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.delivery.DeliveryTracking;

/**
 * The custody trail (C4). Rows here are append-only evidence — each one records
 * that a transfer happened, who recorded it and where — and a delivery's status
 * is a consequence of them rather than something set alongside them.
 *
 * <p>There is deliberately no update or delete method. Correcting a link in the
 * chain means appending the correction, not editing the history.
 */
@Repository
public interface DeliveryTrackingRepository extends JpaRepository<DeliveryTracking, Long> {

    /** One parcel's trail, oldest first. */
    List<DeliveryTracking> findByDeliveryIdOrderByRecordedAtAsc(Long deliveryId);

    /**
     * The trail for several parcels in one query. An order is split across
     * vendors and each vendor's goods travel separately, so rendering one
     * order's timeline touches as many parcels as there are lines — calling the
     * single-parcel method in a loop is a query per line.
     *
     * <p>Returns an empty list for an empty argument rather than issuing
     * {@code IN ()}, which MySQL rejects outright.
     */
    default List<DeliveryTracking> findTrail(Collection<Long> deliveryIds) {
        return deliveryIds == null || deliveryIds.isEmpty()
                ? List.of()
                : findByDeliveryIds(deliveryIds);
    }

    @Query("SELECT t FROM DeliveryTracking t " +
           "WHERE t.delivery.id IN :deliveryIds " +
           "ORDER BY t.recordedAt ASC, t.id ASC")
    List<DeliveryTracking> findByDeliveryIds(@Param("deliveryIds") Collection<Long> deliveryIds);

    /** The most recent event on a parcel, or empty when nothing has happened yet. */
    java.util.Optional<DeliveryTracking> findFirstByDeliveryIdOrderByRecordedAtDescIdDesc(Long deliveryId);

    long countByDeliveryId(Long deliveryId);
}
