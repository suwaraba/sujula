package com.sujula.service.analytics.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.response.analytics.AnalyticsResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryStatus;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;
import com.sujula.repository.analytics.ProductViewStatRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.analytics.VendorAnalyticsService;
import com.sujula.service.reference.CurrencyCatalogue;

import lombok.extern.slf4j.Slf4j;

/**
 * A seller's own figures, and nothing that is not theirs.
 *
 * <p>Every number here comes from the {@code *_native} columns — the vendor's
 * own currency, frozen at the rate the order carried. Nothing in this class
 * reads a rate table, which is what makes a report for last March read the same
 * today as it did in April (C2).
 *
 * <p>The windows are closed-open: {@code from} inclusive, {@code to} exclusive.
 * That is not pedantry. A closed window either double-counts the boundary day
 * when two periods are placed side by side, or drops it, and both show up as a
 * dashboard whose month totals do not add to its year.
 */
@Slf4j
@Service
public class VendorAnalyticsServiceImpl implements VendorAnalyticsService {

    /**
     * Below this, a destination row is suppressed.
     *
     * <p>"One order to Sweden" on a page that also lists what was in it names a
     * customer without using their name. Five is the usual floor for this kind
     * of small-cell suppression and is low enough to keep the page useful.
     */
    private static final long SMALL_COUNT_FLOOR = 5;

    /** A window longer than this is refused rather than quietly truncated. */
    private static final long MAX_WINDOW_DAYS = 400;

    private static final String VIEW_BASIS =
            "Views count page loads of the public listing, including reloads and the same person "
          + "more than once. They are not unique visitors, so treat the conversion rate as a "
          + "trend rather than a true rate.";

    private final VendorOrderRepository vendorOrders;
    private final VendorRepository vendors;
    private final ProductViewStatRepository views;
    private final ProductRepository products;
    private final CurrencyCatalogue currencies;

    public VendorAnalyticsServiceImpl(VendorOrderRepository vendorOrders, VendorRepository vendors,
                                      ProductViewStatRepository views, ProductRepository products,
                                      CurrencyCatalogue currencies) {
        this.vendorOrders = vendorOrders;
        this.vendors = vendors;
        this.views = views;
        this.products = products;
        this.currencies = currencies;
    }

    // ── Overview ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public AnalyticsResponses.Overview overview(Long vendorUserId, LocalDate from, LocalDate to) {
        Vendor vendor = requireVendor(vendorUserId);
        Window now = window(from, to);
        // The same length immediately before, so a 28-day February against 31
        // days of January does not show a fall that is only a calendar.
        Window before = now.precedingOfSameLength();

        Map<String, Money> current = moneyByCurrency(vendor.getId(), now);
        Map<String, Money> previous = moneyByCurrency(vendor.getId(), before);
        Map<String, BigDecimal> refunded = decimalsByCurrency(
                vendorOrders.refundedByCurrency(vendor.getId(), now.fromAt(), now.toAt()));

        List<AnalyticsResponses.OverviewMoney> byCurrency = new ArrayList<>();
        for (String currency : new TreeSet<>(union(current.keySet(), previous.keySet()))) {
            Money mine = current.getOrDefault(currency, Money.EMPTY);
            Money theirs = previous.getOrDefault(currency, Money.EMPTY);
            BigDecimal net = round(mine.revenue.subtract(mine.commission), currency);

            byCurrency.add(new AnalyticsResponses.OverviewMoney(
                    currency,
                    round(mine.revenue, currency), round(theirs.revenue, currency),
                    percentChange(theirs.revenue, mine.revenue),
                    round(mine.commission, currency), net,
                    average(mine.revenue, mine.orders, currency),
                    average(theirs.revenue, theirs.orders, currency),
                    round(refunded.getOrDefault(currency, BigDecimal.ZERO), currency)));
        }

        long orders = current.values().stream().mapToLong(m -> m.orders).sum();
        long ordersBefore = previous.values().stream().mapToLong(m -> m.orders).sum();
        long viewsNow = views.totalViewsForVendor(vendor.getId(), now.from(), now.to());
        long viewsBefore = views.totalViewsForVendor(vendor.getId(), before.from(), before.to());

        return new AnalyticsResponses.Overview(
                now.asPeriod(), before.asPeriod(), byCurrency,
                orders, ordersBefore, percentChange(BigDecimal.valueOf(ordersBefore),
                                                    BigDecimal.valueOf(orders)),
                vendorOrders.unitsSold(vendor.getId(), now.fromAt(), now.toAt()),
                viewsNow, viewsBefore,
                conversion(viewsNow, orders), conversion(viewsBefore, ordersBefore),
                VIEW_BASIS,
                vendorOrders.countInWindowWithStatus(vendor.getId(), now.fromAt(), now.toAt(),
                        VendorOrderStatus.CANCELLED),
                vendorOrders.countInWindowWithStatus(vendor.getId(), now.fromAt(), now.toAt(),
                        VendorOrderStatus.REFUNDED),
                // Counting orders and units across currencies is fine — they are
                // counts. Money is not, and never appears outside byCurrency.
                byCurrency.size() > 1
                        ? "This shop has traded in more than one currency. Each is reported "
                          + "separately; they are deliberately not added together, because no "
                          + "single rate would be true of all these orders."
                        : null);
    }

    // ── Sales ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public AnalyticsResponses.Sales sales(Long vendorUserId, LocalDate from, LocalDate to,
                                          String groupBy) {
        Vendor vendor = requireVendor(vendorUserId);
        Window window = window(from, to);
        Grouping grouping = Grouping.of(groupBy);

        // Daily out of the database, bucketed here. One query whatever the
        // grouping, and the bucket boundaries are computed once in Java rather
        // than three times in SQL dialect-specific date functions.
        Map<String, Map<LocalDate, Bucket>> byCurrency = new LinkedHashMap<>();
        for (Object[] row : vendorOrders.dailyRevenueByCurrency(
                vendor.getId(), window.fromAt(), window.toAt())) {
            String currency = (String) row[0];
            LocalDate day = toLocalDate(row[1]);
            Bucket bucket = byCurrency
                    .computeIfAbsent(currency, key -> new LinkedHashMap<>())
                    .computeIfAbsent(grouping.startOf(day, window.from()), key -> new Bucket());
            bucket.revenue = bucket.revenue.add(decimal(row[2]));
            bucket.commission = bucket.commission.add(decimal(row[3]));
            bucket.orders += number(row[4]);
        }
        for (Object[] row : vendorOrders.dailyUnitsByCurrency(
                vendor.getId(), window.fromAt(), window.toAt())) {
            String currency = (String) row[0];
            LocalDate bucketStart = grouping.startOf(toLocalDate(row[1]), window.from());
            Map<LocalDate, Bucket> buckets = byCurrency.get(currency);
            if (buckets != null && buckets.containsKey(bucketStart)) {
                buckets.get(bucketStart).units += number(row[2]);
            }
        }

        List<AnalyticsResponses.SalesSeries> series = new ArrayList<>();
        for (String currency : new TreeSet<>(byCurrency.keySet())) {
            Map<LocalDate, Bucket> buckets = byCurrency.get(currency);
            List<AnalyticsResponses.SalesPoint> points = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal commission = BigDecimal.ZERO;
            long orders = 0;

            // Every bucket, including the empty ones. A chart drawn from a sparse
            // series closes the gaps and makes a dead fortnight look like a slow
            // week.
            for (LocalDate start : grouping.bucketsIn(window)) {
                LocalDate end = grouping.endOf(start, window);
                Bucket bucket = buckets.getOrDefault(start, new Bucket());
                BigDecimal revenue = round(bucket.revenue, currency);
                BigDecimal took = round(bucket.commission, currency);
                points.add(new AnalyticsResponses.SalesPoint(start, end, revenue, took,
                        round(revenue.subtract(took), currency), bucket.orders, bucket.units));
                total = total.add(revenue);
                commission = commission.add(took);
                orders += bucket.orders;
            }
            series.add(new AnalyticsResponses.SalesSeries(currency, points,
                    round(total, currency), round(commission, currency), orders));
        }

        return new AnalyticsResponses.Sales(window.asPeriod(), grouping.name().toLowerCase(), series,
                series.size() > 1
                        ? "One series per currency. They are not combined: doing so would need a "
                          + "rate that was never applied to these orders."
                        : null);
    }

    // ── Products ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public AnalyticsResponses.Products products(Long vendorUserId, LocalDate from, LocalDate to,
                                                int limit) {
        Vendor vendor = requireVendor(vendorUserId);
        Window window = window(from, to);
        int cap = Math.clamp(limit, 1, 200);

        Map<Long, Long> viewsByProduct = new HashMap<>();
        for (Object[] row : views.viewsByProduct(vendor.getId(), window.from(), window.to())) {
            viewsByProduct.put(number(row[0]), number(row[1]));
        }

        List<Object[]> performance = vendorOrders.productPerformance(
                vendor.getId(), window.fromAt(), window.toAt());

        List<AnalyticsResponses.ProductRow> rows = new ArrayList<>();
        long days = window.days();
        for (Object[] row : performance.subList(0, Math.min(cap, performance.size()))) {
            Long productId = number(row[0]);
            String currency = (String) row[5];
            long units = number(row[3]);
            long seen = viewsByProduct.getOrDefault(productId, 0L);
            Integer stock = products.findById(productId).map(Product::getStock).orElse(null);

            rows.add(new AnalyticsResponses.ProductRow(
                    productId, (String) row[1], (String) row[2],
                    units, round(decimal(row[4]), currency), currency,
                    seen, conversion(seen, units),
                    stock, stockTurn(units, stock, days),
                    stock == null || stock == 0
                            ? "Stock turn needs a stock figure to divide by; this listing has none."
                            : null));
        }

        long ordersWithAProduct = vendorOrders.ordersContainingAProduct(
                vendor.getId(), window.fromAt(), window.toAt());
        long totalViews = views.totalViewsForVendor(vendor.getId(), window.from(), window.to());
        long totalUnits = vendorOrders.unitsSold(vendor.getId(), window.fromAt(), window.toAt());

        return new AnalyticsResponses.Products(window.asPeriod(), rows,
                new AnalyticsResponses.Funnel(totalViews, ordersWithAProduct, totalUnits,
                        conversion(totalViews, ordersWithAProduct), VIEW_BASIS),
                rows.stream().map(AnalyticsResponses.ProductRow::currency).distinct().count() > 1
                        ? "Rows are in the currency each product was sold in, which is not the "
                          + "same for all of them here."
                        : null);
    }

    // ── Customers ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public AnalyticsResponses.Customers customers(Long vendorUserId, LocalDate from, LocalDate to) {
        Vendor vendor = requireVendor(vendorUserId);
        Window window = window(from, to);

        // First-purchase dates are fetched as ids, classified, and dropped. No
        // identifier survives into the response: a seller learns how many people
        // came back, never which.
        Map<Long, LocalDateTime> firstBought = new HashMap<>();
        for (Object[] row : vendorOrders.buyerFirstPurchase(vendor.getId())) {
            firstBought.put(number(row[0]), (LocalDateTime) row[1]);
        }
        List<Long> active = vendorOrders.buyersInWindow(vendor.getId(), window.fromAt(), window.toAt());

        long fresh = 0;
        for (Long buyer : active) {
            LocalDateTime first = firstBought.get(buyer);
            // New means their first ever order from this shop falls inside the
            // window. Anything else is somebody who had bought before.
            if (first != null && !first.isBefore(window.fromAt()) && first.isBefore(window.toAt())) {
                fresh++;
            }
        }
        long buyers = active.size();
        long returning = buyers - fresh;

        List<AnalyticsResponses.DestinationRow> destinations = new ArrayList<>();
        int suppressed = 0;
        for (Object[] row : vendorOrders.destinationCountries(
                vendor.getId(), window.fromAt(), window.toAt())) {
            String country = (String) row[0];
            long orderCount = number(row[1]);
            if (country == null) {
                continue;
            }
            if (orderCount < SMALL_COUNT_FLOOR) {
                // A single order to an unusual country, beside a page that says
                // what was in it, is a named customer.
                suppressed++;
                continue;
            }
            destinations.add(new AnalyticsResponses.DestinationRow(country, orderCount, number(row[2])));
        }

        return new AnalyticsResponses.Customers(window.asPeriod(),
                buyers, fresh, returning,
                buyers == 0 ? null : percent(returning, buyers),
                destinations, suppressed,
                "Destinations are where parcels went, not where buyers paid from. Countries with "
                        + "fewer than " + SMALL_COUNT_FLOOR + " orders are not listed, because a "
                        + "single order to one place identifies the person who placed it.",
                buyers == 0 ? "Nobody bought in this period." : null);
    }

    // ── Delivery ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public AnalyticsResponses.Delivery delivery(Long vendorUserId, LocalDate from, LocalDate to) {
        Vendor vendor = requireVendor(vendorUserId);
        Window window = window(from, to);

        long parcels = 0;
        long delivered = 0;
        long failed = 0;
        long inTransit = 0;
        Map<String, long[]> zones = new LinkedHashMap<>();
        Map<String, String> zoneCountry = new HashMap<>();

        for (Object[] row : vendorOrders.parcelsByStatusAndZone(
                vendor.getId(), window.fromAt(), window.toAt())) {
            DeliveryStatus status = (DeliveryStatus) row[0];
            String zone = row[1] == null ? "Unknown" : (String) row[1];
            zoneCountry.put(zone, (String) row[2]);
            long count = number(row[3]);

            parcels += count;
            if (status == DeliveryStatus.DELIVERED) {
                delivered += count;
            } else if (status == DeliveryStatus.FAILED || status == DeliveryStatus.RETURNED) {
                failed += count;
            } else {
                inTransit += count;
            }

            long[] tally = zones.computeIfAbsent(zone, key -> new long[2]);
            tally[0] += count;
            if (status == DeliveryStatus.FAILED || status == DeliveryStatus.RETURNED) {
                tally[1] += count;
            }
        }

        List<AnalyticsResponses.ZoneRow> failuresByZone = new ArrayList<>();
        for (Map.Entry<String, long[]> zone : zones.entrySet()) {
            if (zone.getValue()[1] == 0) {
                continue;   // a zone with no failures is not a row on a failure list
            }
            failuresByZone.add(new AnalyticsResponses.ZoneRow(
                    zone.getKey(), zoneCountry.get(zone.getKey()),
                    zone.getValue()[0], zone.getValue()[1],
                    percent(zone.getValue()[1], zone.getValue()[0])));
        }
        failuresByZone.sort((a, b) -> Long.compare(b.failed(), a.failed()));

        return new AnalyticsResponses.Delivery(window.asPeriod(),
                parcels, delivered, failed, inTransit,
                // Out of parcels that reached an outcome. Counting the ones still
                // moving as failures would make a busy week look like a crisis.
                delivered + failed == 0 ? null : percent(delivered, delivered + failed),
                averageHours(vendorOrders.fulfilmentTimestamps(
                        vendor.getId(), window.fromAt(), window.toAt()), 0, 1),
                averageHours(vendorOrders.readyToDelivered(
                        vendor.getId(), window.fromAt(), window.toAt()), 0, 1),
                failuresByZone,
                parcels == 0 ? "No parcels went out in this period." : null);
    }

    // ── Windows ──────────────────────────────────────────────────────────────

    /**
     * A closed-open window: {@code from} inclusive, {@code to} exclusive.
     *
     * <p>Half-open because the alternative double-counts or drops the boundary
     * day whenever two periods are placed side by side, which surfaces as a
     * dashboard whose months do not add up to its year.
     */
    private record Window(LocalDate from, LocalDate to) {

        LocalDateTime fromAt() {
            return from.atStartOfDay();
        }

        LocalDateTime toAt() {
            return to.atStartOfDay();
        }

        long days() {
            return ChronoUnit.DAYS.between(from, to);
        }

        Window precedingOfSameLength() {
            long length = days();
            return new Window(from.minusDays(length), from);
        }

        AnalyticsResponses.Period asPeriod() {
            return new AnalyticsResponses.Period(from, to, days());
        }
    }

    /** Defaults to the last 30 days, and refuses a window it cannot serve. */
    private static Window window(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now().plusDays(1);
        LocalDate start = from != null ? from : end.minusDays(30);
        if (!start.isBefore(end)) {
            throw new BadRequestException(
                    "The start of the period must come before the end. Got " + start + " to " + end + ".");
        }
        long length = ChronoUnit.DAYS.between(start, end);
        if (length > MAX_WINDOW_DAYS) {
            // Refused rather than silently truncated: a dashboard that quietly
            // shortened the window would show a figure the seller did not ask for
            // and could not tell apart from the one they did.
            throw new BadRequestException(
                    "That period is " + length + " days. The most this report covers at once is "
                            + MAX_WINDOW_DAYS + " — ask for a shorter window, or use a statement.");
        }
        return new Window(start, end);
    }

    /** How days are folded into buckets. */
    private enum Grouping {
        DAY, WEEK, MONTH;

        static Grouping of(String raw) {
            if (raw == null || raw.isBlank()) {
                return DAY;
            }
            return switch (raw.trim().toLowerCase()) {
                case "day", "daily" -> DAY;
                case "week", "weekly" -> WEEK;
                case "month", "monthly" -> MONTH;
                default -> throw new BadRequestException(
                        "Group by day, week or month. '" + raw + "' is none of those.");
            };
        }

        /**
         * The bucket a day belongs to.
         *
         * <p>Weeks are anchored to the window's own start rather than to Monday,
         * so "the last 90 days by week" gives whole weeks from the day asked for
         * instead of a stub at each end.
         */
        LocalDate startOf(LocalDate day, LocalDate windowStart) {
            return switch (this) {
                case DAY -> day;
                case WEEK -> windowStart.plusDays(
                        (ChronoUnit.DAYS.between(windowStart, day) / 7) * 7);
                case MONTH -> day.withDayOfMonth(1);
            };
        }

        LocalDate endOf(LocalDate start, Window window) {
            LocalDate naive = switch (this) {
                case DAY -> start.plusDays(1);
                case WEEK -> start.plusWeeks(1);
                case MONTH -> start.plusMonths(1);
            };
            // The last bucket stops at the window, not past it, so its figures
            // cover exactly what was asked for.
            return naive.isAfter(window.to()) ? window.to() : naive;
        }

        List<LocalDate> bucketsIn(Window window) {
            List<LocalDate> buckets = new ArrayList<>();
            LocalDate cursor = startOf(window.from(), window.from());
            while (cursor.isBefore(window.to())) {
                buckets.add(cursor);
                cursor = switch (this) {
                    case DAY -> cursor.plusDays(1);
                    case WEEK -> cursor.plusWeeks(1);
                    case MONTH -> cursor.plusMonths(1);
                };
            }
            return buckets;
        }
    }

    private static final class Bucket {
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        long orders;
        long units;
    }

    private record Money(BigDecimal revenue, BigDecimal commission, long orders) {
        static final Money EMPTY = new Money(BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private Map<String, Money> moneyByCurrency(Long vendorId, Window window) {
        Map<String, Money> out = new LinkedHashMap<>();
        for (Object[] row : vendorOrders.revenueByCurrency(vendorId, window.fromAt(), window.toAt())) {
            out.put((String) row[0], new Money(decimal(row[1]), decimal(row[2]), number(row[3])));
        }
        return out;
    }

    private static Map<String, BigDecimal> decimalsByCurrency(List<Object[]> rows) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.put((String) row[0], decimal(row[1]));
        }
        return out;
    }

    // ── Arithmetic ───────────────────────────────────────────────────────────

    /**
     * Percentage change, or null when there is no baseline.
     *
     * <p>Null rather than 100 or infinity. Going from nothing to something is
     * not a percentage increase, and rendering one invites a seller to read
     * their first sale as growth.
     */
    private static Double percentChange(BigDecimal before, BigDecimal after) {
        if (before == null || before.signum() == 0) {
            return null;
        }
        return after.subtract(before)
                .divide(before.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static Double conversion(long views, long converted) {
        return views <= 0 ? null : percent(converted, views);
    }

    private static Double percent(long part, long whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private BigDecimal average(BigDecimal total, long count, String currency) {
        if (count <= 0) {
            return BigDecimal.ZERO;
        }
        return round(total.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP), currency);
    }

    /**
     * Times the shelf turned over in the window, annualised to a year.
     *
     * <p>Null when there is nothing to divide by. A product with no stock left
     * has an infinite turn, which is not a number to put on a dashboard.
     */
    private static Double stockTurn(long unitsSold, Integer stockOnHand, long days) {
        if (stockOnHand == null || stockOnHand <= 0 || days <= 0 || unitsSold <= 0) {
            return null;
        }
        double perDay = (double) unitsSold / days;
        return BigDecimal.valueOf(perDay * 365 / stockOnHand)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Mean hours between two timestamp columns, skipping rows missing either. */
    private static Double averageHours(List<Object[]> rows, int startIndex, int endIndex) {
        long counted = 0;
        double total = 0;
        for (Object[] row : rows) {
            if (row[startIndex] == null || row[endIndex] == null) {
                continue;
            }
            LocalDateTime start = (LocalDateTime) row[startIndex];
            LocalDateTime end = (LocalDateTime) row[endIndex];
            if (end.isBefore(start)) {
                // Clock skew or a backfilled row. Counted as zero would drag the
                // mean down with a duration that did not happen.
                continue;
            }
            total += Duration.between(start, end).toMinutes() / 60.0;
            counted++;
        }
        return counted == 0 ? null
                : BigDecimal.valueOf(total / counted).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal round(BigDecimal amount, String currency) {
        return amount == null ? BigDecimal.ZERO : currencies.round(amount, currency);
    }

    // ── Row plumbing ─────────────────────────────────────────────────────────

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal decimal ? decimal
                : BigDecimal.valueOf(((Number) value).doubleValue());
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** Hibernate hands back java.sql.Date on some dialects and LocalDate on others. */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date sql) {
            return sql.toLocalDate();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        throw new IllegalStateException("Unexpected date type from the database: " + value.getClass());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }

    private Vendor requireVendor(Long vendorUserId) {
        return vendors.findByUserId(vendorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor for user", vendorUserId));
    }
}
