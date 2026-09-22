package com.sujula.repository.shipment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.shipment.CustodyEvent;

@Repository
public interface CustodyEventRepository extends JpaRepository<CustodyEvent, Long> {

    /** The whole chain for a parcel, oldest first. What the status is derived from. */
    List<CustodyEvent> findByShipmentIdOrderByOccurredAtAscIdAsc(Long shipmentId);

    /**
     * Whether this device has already filed this event.
     *
     * <p>What makes an offline upload safe to retry. A phone that syncs, loses
     * signal before the response and syncs again cannot know whether the first
     * attempt landed — so it sends the same client id and this decides.
     */
    Optional<CustodyEvent> findByRecordedByUserIdAndClientEventId(Long userId, String clientEventId);

    boolean existsByShipmentIdAndType(Long shipmentId, CustodyEventType type);

    /** Events of one type on one leg, for "has this driver already said they arrived". */
    @Query("SELECT COUNT(e) FROM CustodyEvent e WHERE e.leg.id = :legId AND e.type = :type")
    long countOnLeg(@Param("legId") Long legId, @Param("type") CustodyEventType type);

    /**
     * A driver's completed handovers in a window, for earnings.
     *
     * <p>Earnings are counted from RELEASED and DEPOSITED events rather than
     * from a leg's status, because an event is a thing that happened and a
     * status is an opinion about it.
     */
    @Query("SELECT e FROM CustodyEvent e WHERE e.recordedByUserId = :userId "
         + "AND e.type IN :types AND e.occurredAt >= :from AND e.occurredAt < :to "
         + "ORDER BY e.occurredAt DESC")
    List<CustodyEvent> findForEarnings(@Param("userId") Long userId,
                                       @Param("types") List<CustodyEventType> types,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    Page<CustodyEvent> findByShipmentId(Long shipmentId, Pageable pageable);
}
