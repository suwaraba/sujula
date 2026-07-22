package com.sujula.repository.order;

import com.sujula.model.OrderItem;
import com.sujula.model.enums.VendorOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);
    List<OrderItem> findByVendorId(Long vendorId);

    /**
     * Paginated order items for a vendor — all statuses, Order + VendorOrder eagerly loaded.
     */
    @Query(value      = "SELECT i FROM OrderItem i " +
                        "LEFT JOIN FETCH i.order o " +
                        "LEFT JOIN FETCH i.vendorOrder vo " +
                        "WHERE i.vendor.id = :vendorId",
           countQuery = "SELECT COUNT(i) FROM OrderItem i WHERE i.vendor.id = :vendorId")
    Page<OrderItem> findByVendorIdFetchOrder(@Param("vendorId") Long vendorId, Pageable pageable);

    /**
     * Order items not yet dispatched from the vendor's premises.
     * Includes items whose VendorOrder status is in the supplied set (PENDING/CONFIRMED/PROCESSING)
     * AND items that have no VendorOrder at all (even more outstanding).
     * Optional {@code productId} narrows the result to a specific product.
     */
    @Query(value      = "SELECT i FROM OrderItem i " +
                        "LEFT JOIN FETCH i.order o " +
                        "LEFT JOIN FETCH i.vendorOrder vo " +
                        "WHERE i.vendor.id = :vendorId " +
                        "AND (:productId IS NULL OR i.product.id = :productId) " +
                        "AND (i.vendorOrder IS NULL OR vo.status IN :statuses)",
           countQuery = "SELECT COUNT(i) FROM OrderItem i " +
                        "LEFT JOIN i.vendorOrder vo " +
                        "WHERE i.vendor.id = :vendorId " +
                        "AND (:productId IS NULL OR i.product.id = :productId) " +
                        "AND (i.vendorOrder IS NULL OR vo.status IN :statuses)")
    Page<OrderItem> findByVendorIdPendingFetchOrder(
            @Param("vendorId")  Long vendorId,
            @Param("productId") Long productId,
            @Param("statuses")  List<VendorOrderStatus> statuses,
            Pageable pageable);

    /**
     * All order items for a specific product within a vendor's catalogue.
     */
    @Query(value      = "SELECT i FROM OrderItem i " +
                        "LEFT JOIN FETCH i.order o " +
                        "LEFT JOIN FETCH i.vendorOrder vo " +
                        "WHERE i.vendor.id = :vendorId AND i.product.id = :productId",
           countQuery = "SELECT COUNT(i) FROM OrderItem i " +
                        "WHERE i.vendor.id = :vendorId AND i.product.id = :productId")
    Page<OrderItem> findByVendorIdAndProductIdFetchOrder(
            @Param("vendorId")  Long vendorId,
            @Param("productId") Long productId,
            Pageable pageable);

    /**
     * Fast count of items not yet dispatched — used by the "Awaiting Dispatch" badge.
     * Counts items where vendorOrder is absent OR status is in the supplied list.
     */
    @Query("SELECT COUNT(i) FROM OrderItem i " +
           "LEFT JOIN i.vendorOrder vo " +
           "WHERE i.vendor.id = :vendorId " +
           "AND (i.vendorOrder IS NULL OR vo.status IN :statuses)")
    long countByVendorIdAndStatuses(
            @Param("vendorId") Long vendorId,
            @Param("statuses") List<VendorOrderStatus> statuses);
}
