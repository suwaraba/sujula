package com.sujula.repository.shipment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.shipment.ShipmentLeg;

@Repository
public interface ShipmentLegRepository extends JpaRepository<ShipmentLeg, Long> {

    /** A leg that is this driver's, by id. Another driver's leg is not found. */
    @Query("SELECT l FROM ShipmentLeg l WHERE l.id = :id AND l.driver.id = :driverId")
    Optional<ShipmentLeg> findByIdAndDriverId(@Param("id") Long id, @Param("driverId") Long driverId);

    /**
     * What is on a driver's screen: offers waiting on them and work they took.
     *
     * <p>Expired offers are filtered in the query rather than after it. A driver
     * shown an offer that has already lapsed will tap it, and a refusal at that
     * point reads as the app being broken rather than as the offer being gone.
     */
    @Query("SELECT l FROM ShipmentLeg l WHERE l.driver.id = :driverId "
         + "AND (l.assignmentStatus IN (com.sujula.model.constant.LegAssignmentStatus.ACCEPTED, "
         + "                            com.sujula.model.constant.LegAssignmentStatus.IN_PROGRESS) "
         + "     OR (l.assignmentStatus = com.sujula.model.constant.LegAssignmentStatus.OFFERED "
         + "         AND (l.offerExpiresAt IS NULL OR l.offerExpiresAt > :now))) "
         + "ORDER BY l.assignmentStatus, l.offerExpiresAt ASC, l.id ASC")
    List<ShipmentLeg> findLiveForDriver(@Param("driverId") Long driverId,
                                        @Param("now") LocalDateTime now);

    /** The legs of one shipment, in travelling order. */
    List<ShipmentLeg> findByShipmentIdOrderBySequenceAsc(Long shipmentId);

    /**
     * The leg a dispatcher is about to act on, locked.
     *
     * <p>PESSIMISTIC_WRITE, and it is the only lock on this surface. Two
     * dispatchers assigning the same parcel within a second of each other is the
     * ordinary race on a busy morning — the loser has to be told rather than
     * silently overwriting the winner, which would leave two drivers each
     * believing the job is theirs and one of them driving to a shop for nothing.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ShipmentLeg l WHERE l.shipment.id = :shipmentId "
         + "AND l.assignmentStatus NOT IN (com.sujula.model.constant.LegAssignmentStatus.COMPLETED, "
         + "                               com.sujula.model.constant.LegAssignmentStatus.CANCELLED) "
         + "ORDER BY l.sequence ASC LIMIT 1")
    Optional<ShipmentLeg> lockNextLeg(@Param("shipmentId") Long shipmentId);

    /** How many parcels a driver is currently answerable for. Ranks the candidates. */
    @Query("SELECT COUNT(l) FROM ShipmentLeg l WHERE l.driver.id = :driverId "
         + "AND l.assignmentStatus IN (com.sujula.model.constant.LegAssignmentStatus.OFFERED, "
         + "                           com.sujula.model.constant.LegAssignmentStatus.ACCEPTED, "
         + "                           com.sujula.model.constant.LegAssignmentStatus.IN_PROGRESS)")
    int countOpenJobs(@Param("driverId") Long driverId);

    /**
     * The leg a driver is currently carrying for a shipment.
     *
     * <p>At most one: a parcel is in one pair of hands. Two rows here would mean
     * a transfer that recorded the new holder without releasing the old one.
     */
    @Query("SELECT l FROM ShipmentLeg l WHERE l.shipment.id = :shipmentId "
         + "AND l.driver.id = :driverId "
         + "AND l.assignmentStatus IN (com.sujula.model.constant.LegAssignmentStatus.ACCEPTED, "
         + "                           com.sujula.model.constant.LegAssignmentStatus.IN_PROGRESS) "
         + "ORDER BY l.sequence ASC LIMIT 1")
    Optional<ShipmentLeg> findActiveLeg(@Param("shipmentId") Long shipmentId,
                                        @Param("driverId") Long driverId);

    /** The next leg after this one, for handing a parcel onward. */
    @Query("SELECT l FROM ShipmentLeg l WHERE l.shipment.id = :shipmentId "
         + "AND l.sequence > :afterSequence ORDER BY l.sequence ASC LIMIT 1")
    Optional<ShipmentLeg> findNextLeg(@Param("shipmentId") Long shipmentId,
                                      @Param("afterSequence") int afterSequence);

    /** How many legs a driver has open, for a fair-dispatch cap. */
    long countByDriverIdAndAssignmentStatusIn(Long driverId, List<LegAssignmentStatus> statuses);

    /** Offers nobody answered, for the sweeper that puts them back in the pool. */
    @Query("SELECT l FROM ShipmentLeg l "
         + "WHERE l.assignmentStatus = com.sujula.model.constant.LegAssignmentStatus.OFFERED "
         + "AND l.offerExpiresAt IS NOT NULL AND l.offerExpiresAt <= :now")
    List<ShipmentLeg> findLapsedOffers(@Param("now") LocalDateTime now);
}
