package com.sujula.repository.aftersales;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.aftersales.Dispute;
import com.sujula.model.constant.DisputeStatus;

@Repository
public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    boolean existsByReference(String reference);

    /**
     * A dispute one of its parties may read.
     *
     * <p>Both sides in the query. A dispute is the one place on this platform
     * where the seller genuinely needs to read what the buyer wrote about them,
     * so "mine" here means either end — and anybody who is neither gets a
     * not-found rather than a refusal that would confirm the case exists.
     */
    @Query("SELECT d FROM Dispute d WHERE d.id = :id "
         + "AND (d.raisedBy.id = :userId OR d.vendorOrder.vendor.user.id = :userId)")
    Optional<Dispute> findByIdForParty(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT d FROM Dispute d WHERE "
         + "(d.raisedBy.id = :userId OR d.vendorOrder.vendor.user.id = :userId) "
         + "AND (:status IS NULL OR d.status = :status) ORDER BY d.createdAt DESC")
    Page<Dispute> findForParty(@Param("userId") Long userId,
                               @Param("status") DisputeStatus status, Pageable pageable);

    /**
     * Disputes still holding a slice's money still.
     *
     * <p>Read before lifting a freeze. Two disputes on one sub-order is unusual
     * and entirely possible — a buyer disputing delivery and then the refund
     * that followed — and closing the first must not pay the seller while the
     * second is open.
     */
    @Query("SELECT d FROM Dispute d WHERE d.vendorOrder.id = :vendorOrderId "
         + "AND d.status IN (com.sujula.model.constant.DisputeStatus.OPEN, "
         + "                 com.sujula.model.constant.DisputeStatus.UNDER_REVIEW)")
    List<Dispute> findFreezingSlice(@Param("vendorOrderId") Long vendorOrderId);
}
