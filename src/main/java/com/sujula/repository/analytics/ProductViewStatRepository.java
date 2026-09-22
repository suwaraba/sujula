package com.sujula.repository.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.analytics.ProductViewStat;

@Repository
public interface ProductViewStatRepository extends JpaRepository<ProductViewStat, Long> {

    Optional<ProductViewStat> findByProductIdAndViewedOn(Long productId, LocalDate viewedOn);

    /**
     * Adds one to today's counter without reading it first.
     *
     * <p>A read-modify-write would lose views under any concurrency at all, and
     * a view counter is the single most concurrent write on the platform. The
     * database does the addition.
     */
    @Modifying
    @Query("UPDATE ProductViewStat s SET s.views = s.views + 1 "
         + "WHERE s.product.id = :productId AND s.viewedOn = :day")
    int increment(@Param("productId") Long productId, @Param("day") LocalDate day);

    /** Views per day across a seller's whole catalogue. */
    @Query("SELECT s.viewedOn, COALESCE(SUM(s.views), 0) FROM ProductViewStat s "
         + "WHERE s.vendor.id = :vendorId AND s.viewedOn >= :from AND s.viewedOn < :to "
         + "GROUP BY s.viewedOn ORDER BY s.viewedOn")
    List<Object[]> dailyViewsForVendor(@Param("vendorId") Long vendorId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Views per product in a window, for the funnel. */
    @Query("SELECT s.product.id, COALESCE(SUM(s.views), 0) FROM ProductViewStat s "
         + "WHERE s.vendor.id = :vendorId AND s.viewedOn >= :from AND s.viewedOn < :to "
         + "GROUP BY s.product.id")
    List<Object[]> viewsByProduct(@Param("vendorId") Long vendorId,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(s.views), 0) FROM ProductViewStat s "
         + "WHERE s.vendor.id = :vendorId AND s.viewedOn >= :from AND s.viewedOn < :to")
    long totalViewsForVendor(@Param("vendorId") Long vendorId,
                             @Param("from") LocalDate from, @Param("to") LocalDate to);
}
