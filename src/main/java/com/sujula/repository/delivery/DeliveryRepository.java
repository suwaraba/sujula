package com.sujula.repository.delivery;

import com.sujula.model.Delivery;
import com.sujula.model.Driver;
import com.sujula.model.enums.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;

import java.util.Optional;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByOrderId(Long orderId);
    Optional<Delivery> findByTrackingNumber(String trackingNumber);

    // Driver-scoped
    Page<Delivery> findByDriverId(Long driverId, Pageable pageable);
    Page<Delivery> findByDriverUserId(Long userId, Pageable pageable);
    Page<Delivery> findByDriverIdAndStatus(Long driverId, DeliveryStatus status, Pageable pageable);
    long countByDriverIdAndStatus(Long driverId, DeliveryStatus status);

    /**
     * Paginated deliveries for a driver with Order, Driver, and PickupPoint eagerly fetched.
     * Avoids LazyInitializationException when building DeliveryResponse (spring.jpa.open-in-view=false).
     */
    @Query(value      = "SELECT d FROM Delivery d " +
                        "LEFT JOIN FETCH d.order " +
                        "LEFT JOIN FETCH d.driver " +
                        "LEFT JOIN FETCH d.pickupPoint " +
                        "WHERE d.driver.id = :driverId",
           countQuery = "SELECT COUNT(d) FROM Delivery d WHERE d.driver.id = :driverId")
    Page<Delivery> findByDriverIdFetchAll(
            @Param("driverId") Long driverId, Pageable pageable);

    /**
     * Sum of {@code distanceKm} for all DELIVERED deliveries of a driver.
     * Returns {@code null} when no completed deliveries exist (i.e. SUM over empty set).
     * Callers must guard against null — do NOT use COALESCE here because
     * {@code COALESCE(SUM(decimal_col), 0)} returns an Integer literal 0
     * which Hibernate cannot automatically coerce to {@code BigDecimal}.
     */
    @Query("SELECT SUM(d.distanceKm) FROM Delivery d " +
           "WHERE d.driver.id = :driverId " +
           "AND d.status = com.sujula.model.enums.DeliveryStatus.DELIVERED")
    java.math.BigDecimal totalDistanceByDriver(@Param("driverId") Long driverId);

    // Available tasks — PENDING deliveries with no driver assigned
    Page<Delivery> findByStatusAndDriverIsNull(DeliveryStatus status, Pageable pageable);

    /**
     * Available tasks filtered by the order's shipping country —
     * locality-aware task discovery for drivers.
     */
    @Query("SELECT d FROM Delivery d " +
           "WHERE d.status = 'PENDING' AND d.driver IS NULL " +
           "  AND d.order.shippingCountry = :countryCode")
    Page<Delivery> findPendingUnassignedByCountry(
            @Param("countryCode") String countryCode, Pageable pageable);

    // Pickup-point scoped
    Page<Delivery> findByPickupPointId(Long pickupPointId, Pageable pageable);
    Page<Delivery> findByPickupPointIdAndStatus(Long pickupPointId, DeliveryStatus status, Pageable pageable);

    // Generic status filter (admin)
    Page<Delivery> findByStatus(DeliveryStatus status, Pageable pageable);

    /**
     * Fetch a delivery together with its OrderItem and the OrderItem's parent Order
     * in a single JOIN FETCH query.  Used by the admin assign flow to avoid N+1 lazy loads
     * when reading recipient info ({@code order.shippingFullName/shippingPhone}).
     */
    @Query("SELECT d FROM Delivery d " +
           "LEFT JOIN FETCH d.orderItem oi " +
           "LEFT JOIN FETCH oi.order " +
           "WHERE d.id = :id")
    Optional<Delivery> findByIdWithOrderDetails(@Param("id") Long id);

    /**
     * Atomically assign a driver to a delivery only if it is still PENDING and unassigned.
     * Returns 1 on success, 0 if another driver already claimed it (race condition detected).
     * Use this instead of load-then-save to prevent two drivers grabbing the same delivery.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.driver = :driver, d.status = 'ASSIGNED', " +
           "d.assignedAt = :now " +
           "WHERE d.id = :id AND d.driver IS NULL AND d.status = 'PENDING'")
    int atomicAssignDriver(@Param("id") Long id,
                           @Param("driver") Driver driver,
                           @Param("now") LocalDateTime now);

    /**
     * Full atomic assign used by the admin flow.
     * Sets driver, status, assignedAt, earningAmount, and recipient info in a single UPDATE
     * — eliminates the separate save() call needed when earningAmount is set after the fact.
     * {@code COALESCE} preserves any recipient values already populated by other flows
     * (vendor fulfilment, pickup-point operators) rather than overwriting them.
     * Returns 1 on success, 0 if the delivery was already claimed or is not PENDING.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d " +
           "SET d.driver        = :driver, " +
           "    d.status        = 'ASSIGNED', " +
           "    d.assignedAt    = :now, " +
           "    d.earningAmount = :earningAmount, " +
           "    d.recipientName  = COALESCE(d.recipientName,  :recipientName), " +
           "    d.recipientPhone = COALESCE(d.recipientPhone, :recipientPhone) " +
           "WHERE d.id = :id AND d.driver IS NULL AND d.status = 'PENDING'")
    int atomicAssignDriverFull(@Param("id")            Long id,
                               @Param("driver")         Driver driver,
                               @Param("now")            LocalDateTime now,
                               @Param("earningAmount")  java.math.BigDecimal earningAmount,
                               @Param("recipientName")  String recipientName,
                               @Param("recipientPhone") String recipientPhone);

    /**
     * Atomically unassign a driver from a delivery, resetting it to PENDING.
     * Only succeeds if the delivery is currently owned by {@code driverId} and not yet DELIVERED.
     * Returns 1 on success, 0 if the delivery was already unassigned, DELIVERED, or belongs to another driver.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Delivery d SET d.driver = null, d.status = 'PENDING', " +
           "d.assignedAt = null, d.earningAmount = null, " +
           "d.recipientName = null, d.recipientPhone = null " +
           "WHERE d.id = :id AND d.driver.id = :driverId " +
           "AND d.status NOT IN ('PENDING', 'DELIVERED')")
    int atomicUnassignDriver(@Param("id") Long id, @Param("driverId") Long driverId);

    /** Pessimistic read lock — used when we need to inspect-then-update atomically in code. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Delivery d WHERE d.id = :id")
    Optional<Delivery> findByIdForUpdate(@Param("id") Long id);

    // ── Analytics queries ─────────────────────────────────────────────────────

    /** Count deliveries by status. */
    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = :status")
    long countByStatus(@Param("status") DeliveryStatus status);

    /**
     * Per-driver delivery stats: driverId, firstName, lastName, deliveryCount, totalEarnings.
     * Average delivery hours is computed in Java from timestamps to stay DB-agnostic.
     */
    @Query(value      = "SELECT d.driver.id, d.driver.user.firstName, d.driver.user.lastName, COUNT(d), d.driver.totalEarnings " +
                        "FROM Delivery d " +
                        "WHERE d.status = com.sujula.model.enums.DeliveryStatus.DELIVERED AND d.driver IS NOT NULL " +
                        "GROUP BY d.driver.id, d.driver.user.firstName, d.driver.user.lastName, d.driver.totalEarnings " +
                        "ORDER BY COUNT(d) DESC",
           countQuery = "SELECT COUNT(DISTINCT d.driver.id) FROM Delivery d WHERE d.status = com.sujula.model.enums.DeliveryStatus.DELIVERED AND d.driver IS NOT NULL")
    Page<Object[]> findDriverDeliveryStats(Pageable pageable);

    /** Count all deliveries delivered on time (deliveredAt <= estimatedDeliveryAt). */
    @Query("SELECT COUNT(d) FROM Delivery d WHERE d.status = com.sujula.model.enums.DeliveryStatus.DELIVERED AND d.deliveredAt IS NOT NULL AND d.estimatedDeliveryAt IS NOT NULL AND d.deliveredAt <= d.estimatedDeliveryAt")
    long countOnTimeDeliveries();

    /**
     * Load assignedAt and deliveredAt for all DELIVERED deliveries where both timestamps exist.
     * Used to compute average delivery hours in Java (DB-agnostic — avoids TIMESTAMPDIFF/EXTRACT dialect issues).
     */
    @Query("SELECT d.assignedAt, d.deliveredAt FROM Delivery d WHERE d.status = com.sujula.model.enums.DeliveryStatus.DELIVERED AND d.assignedAt IS NOT NULL AND d.deliveredAt IS NOT NULL")
    java.util.List<Object[]> findDeliveredTimestamps();
}
