package com.sujula.dto.response.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a seller's dashboard is told.
 *
 * <p>One rule runs through all of it: <strong>a money figure never appears
 * without the currency it is in, and figures in different currencies are never
 * added.</strong> A seller who has traded in dalasi and CFA gets two of
 * everything rather than one number that would require a rate nobody agreed to
 * at a moment nobody can name (C2).
 *
 * <p>That is why almost every money-bearing shape here is nested inside a
 * {@code byCurrency} list rather than sitting at the top level. It makes the
 * common case — one currency, one entry — very slightly more verbose, and makes
 * the wrong answer impossible to express.
 */
public final class AnalyticsResponses {

    private AnalyticsResponses() {}

    // ── Overview ─────────────────────────────────────────────────────────────

    /**
     * The headline numbers, and the same numbers for the period before.
     *
     * <p>The comparison period is the same length immediately preceding, so
     * "this month against last" is a like-for-like even when the months are
     * different lengths — a 28-day February compared against 31 days of January
     * would show a fall that is only a calendar.
     */
    public record Overview(Period period, Period comparedWith,
                           List<OverviewMoney> byCurrency,
                           long orders, long ordersBefore, Double ordersChangePercent,
                           long unitsSold,
                           long productViews, Long productViewsBefore,
                           Double conversionPercent, Double conversionPercentBefore,
                           String conversionNote,
                           long cancelledOrders, long refundedOrders,
                           String note) {}

    /** Revenue in one currency, with the same figure for the preceding period. */
    public record OverviewMoney(String currency,
                                BigDecimal revenue, BigDecimal revenueBefore,
                                Double revenueChangePercent,
                                BigDecimal commission, BigDecimal netRevenue,
                                BigDecimal averageOrderValue, BigDecimal averageOrderValueBefore,
                                BigDecimal refunded) {}

    /** A closed-open window: {@code from} inclusive, {@code to} exclusive. */
    public record Period(LocalDate from, LocalDate to, long days) {}

    // ── Sales ────────────────────────────────────────────────────────────────

    /**
     * A time series, one series per currency.
     *
     * <p>Every bucket is present even where nothing sold, because a chart drawn
     * from a sparse series silently closes the gaps and makes a dead fortnight
     * look like a slow week.
     */
    public record Sales(Period period, String groupBy, List<SalesSeries> byCurrency, String note) {}

    public record SalesSeries(String currency, List<SalesPoint> points,
                              BigDecimal total, BigDecimal commissionTotal, long orders) {}

    public record SalesPoint(LocalDate bucketStart, LocalDate bucketEnd,
                             BigDecimal revenue, BigDecimal commission, BigDecimal net,
                             long orders, long units) {}

    // ── Products ─────────────────────────────────────────────────────────────

    public record Products(Period period, List<ProductRow> rows,
                           Funnel funnel, String note) {}

    /**
     * One listing's performance.
     *
     * <p>Revenue carries its own currency rather than inheriting a page-level
     * one: a seller who relisted a product in a new currency has rows of both,
     * and a single column header would mislabel half of them.
     */
    public record ProductRow(Long productId, String name, String sku,
                             long unitsSold, BigDecimal revenue, String currency,
                             long views, Double conversionPercent,
                             Integer stockOnHand, Double stockTurn, String stockTurnNote) {}

    /**
     * Views to sales.
     *
     * <p>{@code views} counts page loads of the public listing, including
     * reloads and the same person twice. It is not unique visitors, and the note
     * says so — a conversion rate that quietly means something other than what a
     * seller assumes is worse than no conversion rate at all.
     */
    public record Funnel(long views, long ordersContainingAProduct, long unitsSold,
                         Double viewToOrderPercent, String basis) {}

    // ── Customers ────────────────────────────────────────────────────────────

    /**
     * Who is buying, with nobody identifiable.
     *
     * <p>A seller gets counts and destinations, never a person. The destination
     * rows are the delivery country (C1 — where the goods went, not where the
     * payer was) and are suppressed below a small-count floor, because "one
     * order to Sweden" on a page that also lists what was in it is a customer
     * named without naming them.
     */
    public record Customers(Period period,
                            long buyers, long newBuyers, long returningBuyers,
                            Double returningPercent,
                            List<DestinationRow> destinations,
                            int suppressedDestinations, String privacyNote, String note) {}

    public record DestinationRow(String country, long orders, long units) {}

    // ── Delivery ─────────────────────────────────────────────────────────────

    public record Delivery(Period period,
                           long parcels, long delivered, long failed, long inTransit,
                           Double successPercent,
                           Double averageHoursToReady, Double averageHoursToDelivered,
                           List<ZoneRow> failuresByZone, String note) {}

    /** A destination town, and how often parcels to it did not arrive. */
    public record ZoneRow(String zone, String country, long parcels, long failed,
                          Double failurePercent) {}
}
