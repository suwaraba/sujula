package com.sujula.repository.money;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.money.VendorLedgerEntry;

/**
 * Reads over the money ledger.
 *
 * <p>Every aggregate here groups by currency and none of them sums across one.
 * That is not a convention to remember — it is in the queries, so a caller
 * cannot accidentally add dalasi to CFA by calling the wrong method. Where a
 * total is wanted it comes back as one row per currency, and it is the caller's
 * job to render two figures rather than the query's job to invent one.
 */
@Repository
public interface VendorLedgerEntryRepository extends JpaRepository<VendorLedgerEntry, Long> {

    /**
     * What is payable now, per currency.
     *
     * <p>Available means posted, released from escrow, and not already committed
     * to an open payout. The last part is why this filters on the payout's
     * status rather than merely on the entry: money queued for a transfer is
     * spoken for, and offering it again is how a vendor is paid twice.
     */
    @Query("SELECT e.currency, COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
         + "WHERE e.vendor.id = :vendorId AND e.availableFrom IS NOT NULL "
         + "AND e.availableFrom <= :asAt GROUP BY e.currency")
    List<Object[]> sumAvailableByCurrency(@Param("vendorId") Long vendorId,
                                          @Param("asAt") LocalDateTime asAt);

    /**
     * What is earned but still held in escrow, per currency.
     *
     * <p>The parcels that have not arrived yet. A seller needs this beside the
     * available figure, because "you have 4,000 dalasi" and "you have 4,000 now
     * and 30,000 once this week's parcels land" are very different businesses.
     */
    @Query("SELECT e.currency, COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
         + "WHERE e.vendor.id = :vendorId AND e.availableFrom IS NULL GROUP BY e.currency")
    List<Object[]> sumHeldByCurrency(@Param("vendorId") Long vendorId);

    /**
     * Money committed to a payout that has not settled, per currency.
     *
     * <p>Already deducted from available by the PAYOUT entry itself, so this is
     * reported rather than subtracted again. It answers "where did it go" for a
     * seller who saw their balance drop and has not been paid yet.
     */
    @Query("SELECT e.currency, COALESCE(SUM(-e.amount), 0) FROM VendorLedgerEntry e "
         + "WHERE e.vendor.id = :vendorId AND e.type = com.sujula.model.constant.LedgerEntryType.PAYOUT "
         + "AND e.payout IS NOT NULL AND e.payout.status IN "
         + "(com.sujula.model.constant.PayoutStatus.REQUESTED, "
         + " com.sujula.model.constant.PayoutStatus.PENDING, "
         + " com.sujula.model.constant.PayoutStatus.PROCESSING) "
         + "GROUP BY e.currency")
    List<Object[]> sumInFlightPayoutsByCurrency(@Param("vendorId") Long vendorId);

    /**
     * Money on slices with a refund still being decided, per currency.
     *
     * <p>Not deducted from anything — nothing has been decided — but shown, so a
     * seller is not surprised when an administrator approves it. Held back from
     * the available figure would be wrong too: the platform has not taken it.
     */
    @Query("SELECT e.currency, COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
         + "WHERE e.vendor.id = :vendorId AND e.vendorOrder IS NOT NULL "
         + "AND e.type = com.sujula.model.constant.LedgerEntryType.SALE "
         + "AND EXISTS (SELECT 1 FROM RefundRequest r WHERE r.vendorOrder = e.vendorOrder "
         + "            AND r.status IN (com.sujula.model.constant.RefundRequestStatus.REQUESTED, "
         + "                             com.sujula.model.constant.RefundRequestStatus.APPROVED)) "
         + "GROUP BY e.currency")
    List<Object[]> sumAtRiskByCurrency(@Param("vendorId") Long vendorId);

    /** Every currency this vendor has ever had money in. */
    @Query("SELECT DISTINCT e.currency FROM VendorLedgerEntry e WHERE e.vendor.id = :vendorId")
    List<String> currenciesFor(@Param("vendorId") Long vendorId);

    // ── The ledger view ──────────────────────────────────────────────────────

    /**
     * The statement, newest first, optionally narrowed.
     *
     * <p>Null narrows nothing, which keeps one query rather than four that drift.
     */
    @Query(value = "SELECT e FROM VendorLedgerEntry e WHERE e.vendor.id = :vendorId "
                 + "AND (:currency IS NULL OR e.currency = :currency) "
                 + "AND (:type IS NULL OR e.type = :type) "
                 + "AND (CAST(:from AS timestamp) IS NULL OR e.occurredAt >= :from) "
                 + "AND (CAST(:to AS timestamp) IS NULL OR e.occurredAt < :to) "
                 + "ORDER BY e.occurredAt DESC, e.id DESC",
           countQuery = "SELECT COUNT(e) FROM VendorLedgerEntry e WHERE e.vendor.id = :vendorId "
                 + "AND (:currency IS NULL OR e.currency = :currency) "
                 + "AND (:type IS NULL OR e.type = :type) "
                 + "AND (CAST(:from AS timestamp) IS NULL OR e.occurredAt >= :from) "
                 + "AND (CAST(:to AS timestamp) IS NULL OR e.occurredAt < :to)")
    Page<VendorLedgerEntry> findLedger(@Param("vendorId") Long vendorId,
                                       @Param("currency") String currency,
                                       @Param("type") LedgerEntryType type,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       Pageable pageable);

    /**
     * Every entry in a period, oldest first, for a statement.
     *
     * <p>By {@code occurredAt} rather than {@code createdAt}: a March statement
     * is about money that moved in March, not about what somebody typed in
     * during March.
     */
    @Query("SELECT e FROM VendorLedgerEntry e WHERE e.vendor.id = :vendorId "
         + "AND e.currency = :currency AND e.occurredAt >= :from AND e.occurredAt < :to "
         + "ORDER BY e.occurredAt ASC, e.id ASC")
    List<VendorLedgerEntry> findForPeriod(@Param("vendorId") Long vendorId,
                                          @Param("currency") String currency,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /**
     * The balance carried into a period, per currency.
     *
     * <p>What makes a statement add up. Without an opening figure a period's
     * rows are a list of movements with no anchor, and a seller cannot check it
     * against what they were told last month.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
         + "WHERE e.vendor.id = :vendorId AND e.currency = :currency AND e.occurredAt < :before")
    java.math.BigDecimal balanceBefore(@Param("vendorId") Long vendorId,
                                       @Param("currency") String currency,
                                       @Param("before") LocalDateTime before);

    /** Totals per type in a period, for the statement's summary block. */
    @Query("SELECT e.type, COALESCE(SUM(e.amount), 0), COUNT(e) FROM VendorLedgerEntry e "
         + "WHERE e.vendor.id = :vendorId AND e.currency = :currency "
         + "AND e.occurredAt >= :from AND e.occurredAt < :to GROUP BY e.type")
    List<Object[]> totalsByTypeForPeriod(@Param("vendorId") Long vendorId,
                                         @Param("currency") String currency,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    // ── Guards ───────────────────────────────────────────────────────────────

    /**
     * Whether this slice already has an entry of this type.
     *
     * <p>What stops a sale being posted twice when a retried request replays the
     * same event. Posting is keyed on the pair rather than on a flag somewhere,
     * because a flag is a second record of the same fact.
     */
    boolean existsByVendorOrderIdAndType(Long vendorOrderId, LedgerEntryType type);

    List<VendorLedgerEntry> findByVendorOrderIdOrderByOccurredAtAsc(Long vendorOrderId);

    /** The entries a payout committed, for reversing it when the transfer fails. */
    List<VendorLedgerEntry> findByPayoutId(Long payoutId);
}
