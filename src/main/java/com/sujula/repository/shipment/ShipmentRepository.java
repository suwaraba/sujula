package com.sujula.repository.shipment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.shipment.Shipment;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByReference(String reference);

    boolean existsByReference(String reference);

    Optional<Shipment> findByVendorOrderId(Long vendorOrderId);

    /**
     * The parcel a recipient's link names.
     *
     * <p>By tracking code rather than by id, and that is the whole access model
     * on that surface: the code is unguessable, possession of it is the
     * credential, and what it opens is bounded to what is safe for whoever ends
     * up holding a forwarded message. A miss is not-found rather than forbidden,
     * so nothing here confirms that a guessed code belongs to a live parcel.
     */
    Optional<Shipment> findByTrackingCode(String trackingCode);

    boolean existsByTrackingCode(String trackingCode);

    /**
     * A shipment this driver is actually on.
     *
     * <p>The driver goes into the query through the legs rather than being
     * compared afterwards, so a parcel somebody else is carrying is not found
     * rather than fetched and refused. Not-found matters more here than
     * elsewhere: this row carries a recipient's home address and phone number,
     * and "forbidden" would confirm both that the shipment is real and that the
     * prober guessed a live id.
     */
    @Query("SELECT s FROM Shipment s WHERE s.id = :id "
         + "AND EXISTS (SELECT 1 FROM ShipmentLeg l WHERE l.shipment = s AND l.driver.id = :driverId)")
    Optional<Shipment> findByIdAndDriverId(@Param("id") Long id, @Param("driverId") Long driverId);

    /** Shipments a driver has finished, newest first. */
    @Query(value = "SELECT DISTINCT s FROM Shipment s JOIN s.legs l "
                 + "WHERE l.driver.id = :driverId "
                 + "AND l.assignmentStatus = com.sujula.model.constant.LegAssignmentStatus.COMPLETED "
                 + "ORDER BY s.updatedAt DESC",
           countQuery = "SELECT COUNT(DISTINCT s) FROM Shipment s JOIN s.legs l "
                 + "WHERE l.driver.id = :driverId "
                 + "AND l.assignmentStatus = com.sujula.model.constant.LegAssignmentStatus.COMPLETED")
    Page<Shipment> findHistoryForDriver(@Param("driverId") Long driverId, Pageable pageable);

    List<Shipment> findByStatus(ShipmentStatus status);

    /**
     * The dispatch board.
     *
     * <p>{@code waitingSince} is the filter that matters: a board sorted by age
     * shows what came in first, and a board filtered by how long something has
     * been stuck shows what is going wrong. The second is what a dispatcher
     * opens the screen for.
     */
    @Query("SELECT s FROM Shipment s WHERE "
         + "(:status IS NULL OR s.status = :status) "
         + "AND (:country IS NULL OR s.destinationCountry = :country) "
         + "AND (:driverId IS NULL OR EXISTS (SELECT 1 FROM ShipmentLeg l "
         + "     WHERE l.shipment = s AND l.driver.id = :driverId)) "
         + "AND (:waitingSince IS NULL OR s.updatedAt < :waitingSince) "
         + "ORDER BY s.updatedAt ASC")
    Page<Shipment> findBoard(@Param("status") ShipmentStatus status,
                             @Param("country") String country,
                             @Param("driverId") Long driverId,
                             @Param("waitingSince") java.time.LocalDateTime waitingSince,
                             Pageable pageable);

    /**
     * Parcels nobody is carrying.
     *
     * <p>Read as "has no leg anybody has accepted" rather than by status,
     * because a parcel whose offer lapsed is unassigned again and its status
     * still says DRIVER_OFFERED until the chain is re-derived. The legs are the
     * truth about who has it.
     */
    @Query("SELECT s FROM Shipment s WHERE s.cancelledAt IS NULL "
         + "AND s.status IN (com.sujula.model.constant.ShipmentStatus.AWAITING_COLLECTION, "
         + "                 com.sujula.model.constant.ShipmentStatus.DRIVER_OFFERED) "
         + "AND NOT EXISTS (SELECT 1 FROM ShipmentLeg l WHERE l.shipment = s "
         + "     AND l.assignmentStatus IN (com.sujula.model.constant.LegAssignmentStatus.ACCEPTED, "
         + "                                com.sujula.model.constant.LegAssignmentStatus.IN_PROGRESS)) "
         + "ORDER BY s.createdAt ASC")
    List<Shipment> findUnassigned(Pageable pageable);

    /** Parcels belonging to one order, for the admin order view. */
    @Query("SELECT s FROM Shipment s WHERE s.vendorOrder.order.id = :orderId "
         + "ORDER BY s.id ASC")
    List<Shipment> findByOrderId(@Param("orderId") Long orderId);

    // ── What is on a counter ─────────────────────────────────────────────────

    /**
     * Parcels sitting at one point right now.
     *
     * <p>Read from the shipment's own column rather than by walking the custody
     * chain. The chain is the truth about how a parcel got there; this is the
     * question an operator asks two hundred times a day, and it has to be one
     * indexed read.
     */
    List<Shipment> findByHeldAtPickupPointIdOrderByStoredAtAsc(Long pickupPointId);

    /** Parcels past the day they should have been collected. */
    @Query("SELECT s FROM Shipment s WHERE s.heldAtPickupPoint.id = :pickupPointId "
         + "AND s.storageDeadline IS NOT NULL AND s.storageDeadline < :now "
         + "ORDER BY s.storageDeadline ASC")
    List<Shipment> findOverdueAt(@Param("pickupPointId") Long pickupPointId,
                                 @Param("now") java.time.LocalDateTime now);

    /**
     * Parcels on their way to a point but not yet handed over.
     *
     * <p>Found through the legs, because until somebody accepts one it is a
     * driver's problem rather than a counter's — but an operator expecting six
     * parcels this afternoon wants to know that before they arrive.
     */
    @Query("SELECT DISTINCT s FROM Shipment s JOIN s.legs l "
         + "WHERE l.destinationPickupPoint.id = :pickupPointId "
         + "AND l.assignmentStatus IN (com.sujula.model.constant.LegAssignmentStatus.ACCEPTED, "
         + "                           com.sujula.model.constant.LegAssignmentStatus.IN_PROGRESS) "
         + "AND s.heldAtPickupPoint IS NULL "
         + "ORDER BY s.updatedAt ASC")
    List<Shipment> findIncomingTo(@Param("pickupPointId") Long pickupPointId);

    /** How many a point is holding. What the stored count is recounted from. */
    long countByHeldAtPickupPointId(Long pickupPointId);

    /**
     * A parcel at this point, by id.
     *
     * <p>The point goes into the query, so a parcel on another counter is not
     * found rather than fetched and refused — and this row carries a recipient's
     * name and phone number.
     */
    @Query("SELECT s FROM Shipment s WHERE s.id = :id AND s.heldAtPickupPoint.id = :pickupPointId")
    Optional<Shipment> findAtPickupPoint(@Param("id") Long id,
                                         @Param("pickupPointId") Long pickupPointId);

    /**
     * A parcel a point may accept: on its way here, and not yet on anybody's
     * shelf.
     */
    @Query("SELECT s FROM Shipment s WHERE s.id = :id AND s.heldAtPickupPoint IS NULL "
         + "AND EXISTS (SELECT 1 FROM ShipmentLeg l WHERE l.shipment = s "
         + "            AND l.destinationPickupPoint.id = :pickupPointId)")
    Optional<Shipment> findIncomingById(@Param("id") Long id,
                                        @Param("pickupPointId") Long pickupPointId);

    /** Whether a shelf code is already in use at a point. */
    boolean existsByHeldAtPickupPointIdAndShelfCode(Long pickupPointId, String shelfCode);

    /** Everything a point has handled, for earnings. */
    @Query("SELECT s FROM Shipment s WHERE s.pickupCommission IS NOT NULL "
         + "AND s.storedAt >= :from AND s.storedAt < :to "
         + "AND EXISTS (SELECT 1 FROM ShipmentLeg l WHERE l.shipment = s "
         + "            AND (l.destinationPickupPoint.id = :pickupPointId "
         + "                 OR l.originPickupPoint.id = :pickupPointId)) "
         + "ORDER BY s.storedAt DESC")
    List<Shipment> findHandledBy(@Param("pickupPointId") Long pickupPointId,
                                 @Param("from") java.time.LocalDateTime from,
                                 @Param("to") java.time.LocalDateTime to);
}
