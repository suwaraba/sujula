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
