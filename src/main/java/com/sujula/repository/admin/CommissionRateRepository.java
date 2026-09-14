package com.sujula.repository.admin;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.admin.CommissionRate;

@Repository
public interface CommissionRateRepository extends JpaRepository<CommissionRate, Long> {

    /**
     * The rate in force for a store at a moment.
     *
     * <p>The moment is a parameter rather than "now", which is the whole point
     * of the table: settling an order taken in September has to read September's
     * rate, and a query that could only answer for today would silently settle
     * it at today's.
     *
     * <p>Most specific first — a rate for this vendor beats the platform
     * default — and newest first within that, so a same-day change wins.
     */
    @Query("SELECT c FROM CommissionRate c WHERE (c.vendor.id = :vendorId OR c.vendor IS NULL) "
         + "AND c.category IS NULL "
         + "AND c.effectiveFrom <= :at "
         + "AND (c.effectiveUntil IS NULL OR c.effectiveUntil > :at) "
         + "ORDER BY CASE WHEN c.vendor IS NULL THEN 1 ELSE 0 END, c.effectiveFrom DESC")
    List<CommissionRate> findApplicable(@Param("vendorId") Long vendorId,
                                        @Param("at") LocalDateTime at);

    /** Everything ever agreed with this store, newest first. What a seller is shown. */
    @Query("SELECT c FROM CommissionRate c WHERE c.vendor.id = :vendorId "
         + "ORDER BY c.effectiveFrom DESC, c.id DESC")
    List<CommissionRate> findHistory(@Param("vendorId") Long vendorId);

    /**
     * The open-ended row for this store, if there is one.
     *
     * <p>Closed by the service when a later rate is written, so the two never
     * both claim to be in force.
     */
    @Query("SELECT c FROM CommissionRate c WHERE c.vendor.id = :vendorId "
         + "AND c.category IS NULL AND c.effectiveUntil IS NULL "
         + "ORDER BY c.effectiveFrom DESC LIMIT 1")
    Optional<CommissionRate> findOpenEnded(@Param("vendorId") Long vendorId);

    /** Rates that start in the future — what a seller sees as "from next month". */
    @Query("SELECT c FROM CommissionRate c WHERE c.vendor.id = :vendorId "
         + "AND c.effectiveFrom > :now ORDER BY c.effectiveFrom ASC")
    List<CommissionRate> findScheduled(@Param("vendorId") Long vendorId,
                                       @Param("now") LocalDateTime now);
}
