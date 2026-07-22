package com.sujula.repository.order;

import com.sujula.model.Order;
import com.sujula.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order>     findByCustomerId(Long customerId, Pageable pageable);
    Optional<Order> findByOrderNumber(String orderNumber);
    Page<Order>     findByStatus(OrderStatus status, Pageable pageable);
    Page<Order>     findByItems_VendorId(Long vendorId, Pageable pageable);
    boolean         existsByCustomerIdAndStatusAndItems_ProductId(
                        Long customerId, OrderStatus status, Long productId);

    /**
     * Guest-order lookup — requires both orderNumber AND guestEmail so the
     * caller cannot enumerate orders by number alone.
     */
    Optional<Order> findByOrderNumberAndGuestEmailIgnoreCase(
                        String orderNumber, String guestEmail);

    /** Aggregate: total revenue from DELIVERED orders (avoids full table scan). */
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o WHERE o.status = 'DELIVERED'")
    java.math.BigDecimal sumDeliveredRevenue();

    /** Count orders by status (single query). */
    long countByStatus(OrderStatus status);

    // ── Revenue analytics queries ─────────────────────────────────────────────

    /**
     * All orders with the given status whose createdAt falls within [from, to].
     * Used by revenue service — only selects simple scalar fields (no lazy joins needed).
     */
    List<Order> findByStatusAndCreatedAtBetween(
            OrderStatus status, LocalDateTime from, LocalDateTime to);

    /**
     * Count ALL orders created in the given date range (regardless of status).
     * Used to show total order volume alongside revenue figures.
     */
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    // ── Admin queries — eagerly fetch customer to avoid LazyInitializationException ──

    /**
     * Fetch all orders with customer eagerly loaded.
     * Used by admin order list — avoids N+1 queries and LazyInitializationException
     * when spring.jpa.open-in-view=false.
     */
    @Query(value = "SELECT o FROM Order o LEFT JOIN FETCH o.customer",
           countQuery = "SELECT count(o) FROM Order o")
    Page<Order> findAllFetchCustomer(Pageable pageable);

    /**
     * Fetch orders filtered by status with customer eagerly loaded.
     */
    @Query(value = "SELECT o FROM Order o LEFT JOIN FETCH o.customer WHERE o.status = :status",
           countQuery = "SELECT count(o) FROM Order o WHERE o.status = :status")
    Page<Order> findByStatusFetchCustomer(@Param("status") OrderStatus status, Pageable pageable);

    /**
     * Fetch orders for a specific customer with customer eagerly loaded.
     * Used by admin user-profile orders tab.
     */
    @Query(value = "SELECT o FROM Order o LEFT JOIN FETCH o.customer WHERE o.customer.id = :customerId",
           countQuery = "SELECT count(o) FROM Order o WHERE o.customer.id = :customerId")
    Page<Order> findByCustomerIdFetchCustomer(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * Admin single-order fetch.
     * Only JOIN FETCHes customer (to avoid an extra SELECT for the buyer's name/email).
     * Items, product, vendor, vendorOrder are all lazy — they will be resolved by
     * Hibernate within the surrounding {@code @Transactional(readOnly=true)} session
     * in {@code OrderServiceImpl.findById}, so no LazyInitializationException occurs.
     *
     * <p>We intentionally do NOT fetch the {@code items} collection here because
     * {@code Order} has multiple {@code @OneToMany} bags ({@code items}, {@code totals},
     * {@code statusHistory}) and JOIN FETCHing more than one bag in a single JPQL query
     * triggers Hibernate's MultipleBagFetchException.
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.customer WHERE o.id = :id")
    Optional<Order> findByIdFetchAll(@Param("id") Long id);

    // ── Analytics queries ─────────────────────────────────────────────────────

    /** Count distinct customers who placed at least one non-cancelled order since the given time. */
    @Query("SELECT COUNT(DISTINCT o.customer.id) FROM Order o WHERE o.customer IS NOT NULL AND o.createdAt >= :since AND o.status <> com.sujula.model.enums.OrderStatus.CANCELLED")
    long countActiveCustomersSince(@Param("since") LocalDateTime since);

    /** Daily new order counts since the given time (for revenue trend charts). */
    @Query("SELECT CAST(o.createdAt AS date), COUNT(o) FROM Order o WHERE o.createdAt >= :since GROUP BY CAST(o.createdAt AS date) ORDER BY CAST(o.createdAt AS date)")
    List<Object[]> countNewOrdersByDay(@Param("since") LocalDateTime since);

    /** Top customers ranked by total spend — non-cancelled orders only. */
    @Query("SELECT o.customer.id, o.customer.email, o.customer.firstName, o.customer.lastName, COUNT(o), SUM(o.total) FROM Order o WHERE o.customer IS NOT NULL AND o.status <> com.sujula.model.enums.OrderStatus.CANCELLED GROUP BY o.customer.id, o.customer.email, o.customer.firstName, o.customer.lastName ORDER BY SUM(o.total) DESC")
    Page<Object[]> findTopCustomersBySpend(Pageable pageable);

    /** Average order value across all non-cancelled orders. */
    @Query("SELECT AVG(o.total) FROM Order o WHERE o.status <> com.sujula.model.enums.OrderStatus.CANCELLED")
    java.math.BigDecimal findAvgOrderValue();

    /** Top products by revenue — non-cancelled orders. */
    @Query("SELECT oi.product.id, oi.productName, oi.productSku, SUM(oi.quantity), SUM(oi.totalPrice) FROM OrderItem oi WHERE oi.order.status <> com.sujula.model.enums.OrderStatus.CANCELLED GROUP BY oi.product.id, oi.productName, oi.productSku ORDER BY SUM(oi.totalPrice) DESC")
    Page<Object[]> findTopProductsByRevenue(Pageable pageable);

    /** Top products by units sold — non-cancelled orders. */
    @Query("SELECT oi.product.id, oi.productName, oi.productSku, SUM(oi.quantity), SUM(oi.totalPrice) FROM OrderItem oi WHERE oi.order.status <> com.sujula.model.enums.OrderStatus.CANCELLED GROUP BY oi.product.id, oi.productName, oi.productSku ORDER BY SUM(oi.quantity) DESC")
    Page<Object[]> findTopProductsByUnits(Pageable pageable);

    /** Sum of totals for completed (DELIVERED) orders within a time range — used by finance reconciliation. */
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o WHERE o.status = com.sujula.model.enums.OrderStatus.DELIVERED AND o.createdAt BETWEEN :from AND :to")
    java.math.BigDecimal sumDeliveredRevenueBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Full-text search across orderNumber, customer email/name, and guest fields.
     */
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
