package com.sujula.repository.aftersales;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.aftersales.ReturnRequest;
import com.sujula.model.constant.ReturnStatus;

@Repository
public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    boolean existsByReference(String reference);

    /**
     * A return the buyer opened. Anybody else's is not found.
     *
     * <p>The owner goes into the query rather than being compared afterwards.
     * The comparison-afterwards version is the one that ships with the
     * comparison missing, and what it would expose here is another buyer's
     * order, their photographs and what they said about the seller.
     */
    @Query("SELECT r FROM ReturnRequest r WHERE r.id = :id AND r.requestedBy.id = :userId")
    Optional<ReturnRequest> findByIdAndBuyerId(@Param("id") Long id, @Param("userId") Long userId);

    /** A return against this seller's own slice. Anybody else's is not found. */
    @Query("SELECT r FROM ReturnRequest r WHERE r.id = :id AND r.vendorOrder.vendor.id = :vendorId")
    Optional<ReturnRequest> findByIdAndVendorId(@Param("id") Long id,
                                                @Param("vendorId") Long vendorId);

    /**
     * A return either side of it may read.
     *
     * <p>One query rather than two calls and an or, so the two ownership tests
     * cannot drift apart — and so a caller cannot accidentally check only one.
     */
    @Query("SELECT r FROM ReturnRequest r WHERE r.id = :id "
         + "AND (r.requestedBy.id = :userId OR r.vendorOrder.vendor.user.id = :userId)")
    Optional<ReturnRequest> findByIdForParty(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT r FROM ReturnRequest r WHERE r.requestedBy.id = :userId "
         + "AND (:status IS NULL OR r.status = :status) ORDER BY r.createdAt DESC")
    Page<ReturnRequest> findForBuyer(@Param("userId") Long userId,
                                     @Param("status") ReturnStatus status, Pageable pageable);

    @Query("SELECT r FROM ReturnRequest r WHERE r.vendorOrder.vendor.id = :vendorId "
         + "AND (:status IS NULL OR r.status = :status) ORDER BY r.createdAt DESC")
    Page<ReturnRequest> findForVendor(@Param("vendorId") Long vendorId,
                                      @Param("status") ReturnStatus status, Pageable pageable);

    /**
     * Open returns against one slice.
     *
     * <p>What stops a buyer opening a second return for the same goods while the
     * first is still being decided — two returns for one phone is two refunds if
     * two people happen to approve them.
     */
    @Query("SELECT r FROM ReturnRequest r WHERE r.vendorOrder.id = :vendorOrderId "
         + "AND r.status NOT IN (com.sujula.model.constant.ReturnStatus.REFUNDED, "
         + "                     com.sujula.model.constant.ReturnStatus.REJECTED, "
         + "                     com.sujula.model.constant.ReturnStatus.WITHDRAWN)")
    List<ReturnRequest> findOpenForSlice(@Param("vendorOrderId") Long vendorOrderId);

    /**
     * How many of one order item are already spoken for by open returns.
     *
     * <p>Read before a new return is accepted. Without it a buyer who bought two
     * cases can open two returns for one case each and then a third, and the
     * seller ends up refunding three of the two they sold.
     */
    @Query("SELECT COALESCE(SUM(l.quantity), 0) FROM ReturnLine l "
         + "WHERE l.orderItem.id = :orderItemId "
         + "AND l.returnRequest.status NOT IN (com.sujula.model.constant.ReturnStatus.REJECTED, "
         + "                                   com.sujula.model.constant.ReturnStatus.WITHDRAWN)")
    int quantityAlreadyClaimed(@Param("orderItemId") Long orderItemId);
}
