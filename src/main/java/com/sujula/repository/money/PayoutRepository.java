package com.sujula.repository.money;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.user.Payout;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, Long> {

    /**
     * A seller's own transfers, newest first.
     *
     * <p>Scoped by vendor in the query rather than filtered afterwards. This
     * table also holds drivers' and pickup operators' earnings, and a read that
     * fetched by user and then checked would be one forgotten check away from
     * showing a shop somebody else's income.
     */
    @Query(value = "SELECT p FROM Payout p WHERE p.vendor.id = :vendorId "
                 + "AND (:status IS NULL OR p.status = :status) "
                 + "ORDER BY p.createdAt DESC, p.id DESC",
           countQuery = "SELECT COUNT(p) FROM Payout p WHERE p.vendor.id = :vendorId "
                 + "AND (:status IS NULL OR p.status = :status)")
    Page<Payout> findForVendor(@Param("vendorId") Long vendorId,
                               @Param("status") PayoutStatus status, Pageable pageable);

    @Query("SELECT p FROM Payout p WHERE p.id = :id AND p.vendor.id = :vendorId")
    Optional<Payout> findByIdAndVendorId(@Param("id") Long id, @Param("vendorId") Long vendorId);

    /**
     * Transfers in this currency that have not settled.
     *
     * <p>Read before a new request is written. Not to refuse one — a seller may
     * always ask — but because a second request for money the first has already
     * claimed would commit the same balance twice, and the ledger would then owe
     * more than it holds.
     */
    @Query("SELECT p FROM Payout p WHERE p.vendor.id = :vendorId AND p.currency = :currency "
         + "AND p.status IN (com.sujula.model.constant.PayoutStatus.REQUESTED, "
         + "                 com.sujula.model.constant.PayoutStatus.PENDING, "
         + "                 com.sujula.model.constant.PayoutStatus.PROCESSING) "
         + "ORDER BY p.createdAt DESC")
    List<Payout> findOpenForVendor(@Param("vendorId") Long vendorId,
                                   @Param("currency") String currency);

    boolean existsByReference(String reference);
}
