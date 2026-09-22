package com.sujula.repository.order;

import com.sujula.model.constant.OrderStatus;
import com.sujula.model.order.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    /**
     * A customer's order history as a data export needs it: five scalar columns
     * and nothing else.
     *
     * <p>An export covers a lifetime of orders in one pass. Hydrating each into
     * a managed {@code Order} — with its items, its coupon, its address snapshot
     * and its payment — would load several hundred times the data for the five
     * fields the export actually writes, and would pin all of it in the
     * persistence context at once.
     */
    @Query("SELECT o.orderNumber AS orderNumber, o.status AS status, o.total AS total, "
         + "o.currency AS currency, o.createdAt AS placedAt "
         + "FROM Order o WHERE o.customer.id = :customerId ORDER BY o.createdAt DESC")
    List<OrderExportView> findExportRows(@Param("customerId") Long customerId);

    /** The projection {@link #findExportRows} returns. */
    interface OrderExportView {
        String getOrderNumber();
        OrderStatus getStatus();
        BigDecimal getTotal();
        String getCurrency();
        LocalDateTime getPlacedAt();
    }

    /**
     * One order, but only if it is this buyer's.
     *
     * <p>Ownership is the query. Loading by id and comparing the customer
     * afterwards is the shape that eventually ships with the comparison missing,
     * and an order carries an address, a phone number and what somebody spent.
     */
    @Query("SELECT o FROM Order o WHERE o.id = :id AND o.customer.id = :customerId")
    Optional<Order> findByIdAndCustomerId(@Param("id") Long id, @Param("customerId") Long customerId);

    /** The buyer's own orders, newest first, optionally narrowed by status. */
    @Query(value = "SELECT o FROM Order o WHERE o.customer.id = :customerId "
                 + "AND (:status IS NULL OR o.status = :status) ORDER BY o.createdAt DESC",
           countQuery = "SELECT COUNT(o) FROM Order o WHERE o.customer.id = :customerId "
                      + "AND (:status IS NULL OR o.status = :status)")
    Page<Order> findForBuyer(@Param("customerId") Long customerId,
                             @Param("status") OrderStatus status, Pageable pageable);

    /**
     * The order behind a public tracking code.
     *
     * <p>No ownership check, deliberately: the code is the credential, and the
     * person holding it is a recipient who has no account to be checked against.
     * What protects this is that the code is unguessable and the page it serves
     * carries nothing worth stealing.
     */
    Optional<Order> findByTrackingCode(String trackingCode);

    boolean existsByTrackingCode(String trackingCode);

    Optional<Order> findByOrderNumber(String orderNumber);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findByItems_VendorId(Long vendorId, Pageable pageable);

    /**
     * Guest-order lookup — requires both orderNumber AND guestEmail so the
     * caller cannot enumerate orders by number alone.
     */
    Optional<Order> findByOrderNumberAndGuestEmailIgnoreCase(String orderNumber, String guestEmail);

    /** Aggregate: total revenue from DELIVERED orders (avoids a full table scan). */
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o WHERE o.status = :status")
    BigDecimal sumRevenueByStatus(@Param("status") OrderStatus status);

    long countByStatus(OrderStatus status);

    /**
     * The administrative order search.
     *
     * <p>{@code destinationCountry} is a filter in its own right and not a proxy
     * for the buyer: on this platform the payer and the delivery are routinely
     * in different countries (C1), so "orders going to The Gambia" and "orders
     * from Gambian buyers" are different questions and only one of them is what
     * a dispatcher means.
     *
     * <p>{@code stuckSince} is what a stuck-order sweep reads — not how old an
     * order is, but how long it has sat without moving.
     */
    @Query("""
            SELECT o FROM Order o WHERE
              (:status IS NULL OR o.status = :status)
              AND (:destinationCountry IS NULL OR o.shippingCountry = :destinationCountry)
              AND (:vendorId IS NULL OR EXISTS (
                   SELECT 1 FROM VendorOrder vo WHERE vo.order = o AND vo.vendor.id = :vendorId))
              AND (:stuckSince IS NULL OR o.updatedAt < :stuckSince)
              AND (:q IS NULL OR
                   LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', :q, '%')) OR
                   LOWER(o.guestEmail) LIKE LOWER(CONCAT('%', :q, '%')) OR
                   LOWER(o.customer.email) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY o.createdAt DESC
            """)
    Page<Order> adminSearch(@Param("q") String q,
                            @Param("status") OrderStatus status,
                            @Param("destinationCountry") String destinationCountry,
                            @Param("vendorId") Long vendorId,
                            @Param("stuckSince") LocalDateTime stuckSince,
                            Pageable pageable);

    // ── Revenue analytics ────────────────────────────────────────────────────

    /** Orders with the given status created within [from, to]. */
    List<Order> findByStatusAndCreatedAtBetween(OrderStatus status, LocalDateTime from, LocalDateTime to);

    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    // ── Fetch-join reads (avoid LazyInitializationException with open-in-view=false) ──

    @Query(value = "SELECT o FROM Order o LEFT JOIN FETCH o.customer",
           countQuery = "SELECT count(o) FROM Order o")
    Page<Order> findAllFetchCustomer(Pageable pageable);

    @Query(value = "SELECT o FROM Order o LEFT JOIN FETCH o.customer WHERE o.status = :status",
           countQuery = "SELECT count(o) FROM Order o WHERE o.status = :status")
    Page<Order> findByStatusFetchCustomer(@Param("status") OrderStatus status, Pageable pageable);

    @Query(value = "SELECT o FROM Order o LEFT JOIN FETCH o.customer WHERE o.customer.id = :customerId",
           countQuery = "SELECT count(o) FROM Order o WHERE o.customer.id = :customerId")
    Page<Order> findByCustomerIdFetchCustomer(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * Admin single-order fetch. Only JOIN FETCHes {@code customer} — {@code items},
     * {@code vendorOrders} and {@code statusHistory} are all separate {@code @OneToMany}
     * bags, and Hibernate rejects fetch-joining more than one bag in a single JPQL
     * query (MultipleBagFetchException). They are resolved lazily within the
     * surrounding {@code @Transactional(readOnly = true)} session instead.
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.customer WHERE o.id = :id")
    Optional<Order> findByIdFetchAll(@Param("id") Long id);

    // ── Analytics ────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(DISTINCT o.customer.id) FROM Order o "
            + "WHERE o.customer IS NOT NULL AND o.createdAt >= :since AND o.status <> :excludedStatus")
    long countActiveCustomersSince(@Param("since") LocalDateTime since, @Param("excludedStatus") OrderStatus excludedStatus);

    @Query("SELECT o.customer.id, o.customer.email, o.customer.firstName, o.customer.lastName, COUNT(o), SUM(o.total) "
            + "FROM Order o WHERE o.customer IS NOT NULL AND o.status <> :excludedStatus "
            + "GROUP BY o.customer.id, o.customer.email, o.customer.firstName, o.customer.lastName "
            + "ORDER BY SUM(o.total) DESC")
    Page<Object[]> findTopCustomersBySpend(@Param("excludedStatus") OrderStatus excludedStatus, Pageable pageable);

    @Query("SELECT AVG(o.total) FROM Order o WHERE o.status <> :excludedStatus")
    BigDecimal findAvgOrderValue(@Param("excludedStatus") OrderStatus excludedStatus);

    @Query("SELECT oi.product.id, oi.productName, oi.productSku, SUM(oi.quantity), SUM(oi.totalPriceConverted) "
            + "FROM OrderItem oi WHERE oi.order.status <> :excludedStatus "
            + "GROUP BY oi.product.id, oi.productName, oi.productSku ORDER BY SUM(oi.totalPriceConverted) DESC")
    Page<Object[]> findTopProductsByRevenue(@Param("excludedStatus") OrderStatus excludedStatus, Pageable pageable);

    @Query("SELECT oi.product.id, oi.productName, oi.productSku, SUM(oi.quantity), SUM(oi.totalPriceConverted) "
            + "FROM OrderItem oi WHERE oi.order.status <> :excludedStatus "
            + "GROUP BY oi.product.id, oi.productName, oi.productSku ORDER BY SUM(oi.quantity) DESC")
    Page<Object[]> findTopProductsByUnits(@Param("excludedStatus") OrderStatus excludedStatus, Pageable pageable);

    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o WHERE o.status = :status AND o.createdAt BETWEEN :from AND :to")
    BigDecimal sumRevenueByStatusBetween(@Param("status") OrderStatus status,
                                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Full-text search across orderNumber, customer email/name, and guest fields. */
    @Query(value = """
        SELECT o FROM Order o LEFT JOIN FETCH o.customer c WHERE
          LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(c.email, ''))    LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(c.firstName, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(c.lastName, ''))  LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(o.guestEmail, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(o.guestName, ''))  LIKE LOWER(CONCAT('%', :q, '%'))
        """,
           countQuery = """
        SELECT COUNT(o) FROM Order o LEFT JOIN o.customer c WHERE
          LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(c.email, ''))    LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(c.firstName, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(c.lastName, ''))  LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(o.guestEmail, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR
          LOWER(COALESCE(o.guestName, ''))  LIKE LOWER(CONCAT('%', :q, '%'))
        """)
    Page<Order> searchFetchCustomer(@Param("q") String q, Pageable pageable);
}
