package com.sujula.service.analytics;

import java.time.LocalDate;

import com.sujula.dto.response.analytics.AnalyticsResponses;

/**
 * What a seller can learn about their own shop.
 *
 * <p>Two boundaries define this interface, and both are about what it will not
 * tell you.
 *
 * <p><strong>Never one number across two currencies.</strong> Everything money
 * comes back keyed by currency. A vendor who has traded in dalasi and CFA has
 * two revenues, and adding them would need a rate nobody agreed to at a moment
 * nobody can name — which is exactly what C2 forbids. The figures themselves are
 * the {@code *_native} columns, frozen when each order was placed, so a report
 * for last March reads the same today as it did in April.
 *
 * <p><strong>Never a person.</strong> The customer view returns counts and
 * destination countries and nothing else. Destinations are the delivery country
 * — where the parcel went, not where the payer was (C1) — and rows below a small
 * count are suppressed, because a single order to an unusual country, on a page
 * that also says what was in it, names somebody without using their name.
 *
 * <p>Every method resolves the vendor from the authenticated user. No method
 * takes a vendor id from a caller.
 */
public interface VendorAnalyticsService {

    /** Headline figures for a window, against the same length before it. */
    AnalyticsResponses.Overview overview(Long vendorUserId, LocalDate from, LocalDate to);

    /**
     * A time series, one per currency.
     *
     * @param groupBy {@code day}, {@code week} or {@code month}
     */
    AnalyticsResponses.Sales sales(Long vendorUserId, LocalDate from, LocalDate to, String groupBy);

    /** Top sellers, the views-to-sales funnel, and stock turn. */
    AnalyticsResponses.Products products(Long vendorUserId, LocalDate from, LocalDate to, int limit);

    /** New against returning, and where the parcels went. Aggregated, never a person. */
    AnalyticsResponses.Customers customers(Long vendorUserId, LocalDate from, LocalDate to);

    /** Success rate, time to ready, and failures by destination. */
    AnalyticsResponses.Delivery delivery(Long vendorUserId, LocalDate from, LocalDate to);
}
