package com.sujula.service.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.sujula.dto.response.analytics.AnalyticsResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.analytics.ProductViewStat;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.analytics.ProductViewStatRepository;
import com.sujula.repository.order.OrderItemRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.analytics.impl.VendorAnalyticsServiceImpl;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A seller's figures, against a real database.
 *
 * <p>The claims worth testing here are all claims about aggregate SQL: that two
 * currencies stay two, that a window's boundaries include and exclude what they
 * say, and that a buyer is never identifiable from what comes back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({VendorAnalyticsServiceImpl.class, CurrencyCatalogue.class, ReferenceDataProperties.class})
class VendorAnalyticsServiceTest {

    @Autowired private VendorAnalyticsServiceImpl analytics;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private ProductRepository products;
    @Autowired private ProductViewStatRepository views;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private Vendor vendor;
    private User sellerUser;
    private Product kettle;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);

    @BeforeEach
    void setUp() {
        sellerUser = new User();
        sellerUser.setEmail("lamin@sujula.gm");
        sellerUser.setPassword("x");
        sellerUser.setFirstName("Lamin");
        sellerUser.setLastName("Jallow");
        sellerUser.setRole(UserRole.VENDOR);
        sellerUser = users.save(sellerUser);

        vendor = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-electronics")
                .status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD").pickupCountryCode("GM")
                .build());

        kettle = new Product();
        kettle.setName("Kettle");
        kettle.setSku("KET-1");
        kettle.setSlug("kettle");
        kettle.setPrice(new BigDecimal("1300.00"));
        kettle.setPriceCurrency("GMD");
        kettle.setVendor(vendor);
        kettle.setStock(20);
        kettle = products.save(kettle);
    }

    /** A delivered slice on a given day, in a given currency. */
    private VendorOrder sale(String number, LocalDate day, String currency,
                            String gross, String commission, int units, String shipTo,
                            User buyer) {
        Order order = new Order();
        order.setOrderNumber(number);
        order.setSubtotal(new BigDecimal(gross));
        order.setTotal(new BigDecimal(gross));
        order.setCurrency("EUR");
        order.setCustomer(buyer);
        order.setShippingCountry(shipTo);
        order.setShippingCity(shipTo == null ? null : "Serrekunda");
        order = orders.save(order);

        VendorOrder slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(vendor).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency(currency)
                .subtotalNative(new BigDecimal(gross))
                .totalNative(new BigDecimal(gross))
                .commissionNative(new BigDecimal(commission))
                .commissionRate(new BigDecimal("10.00"))
                .subtotal(new BigDecimal(gross)).total(new BigDecimal(gross))
                .acceptedAt(day.atTime(9, 0)).readyAt(day.atTime(13, 0))
                .build());

        orderItems.save(OrderItem.builder()
                .order(order).vendorOrder(slice).product(kettle).vendor(vendor)
                .quantity(units)
                .unitPrice(new BigDecimal(gross)).totalPrice(new BigDecimal(gross))
                .currency(currency).productName("Kettle").productSku("KET-1")
                .build());

        // createdAt is @CreationTimestamp, so it has to be forced to land the
        // row in the window this test is about.
        entityManager.flush();
        entityManager.createQuery(
                "UPDATE VendorOrder v SET v.createdAt = :when WHERE v.id = :id")
                .setParameter("when", day.atTime(10, 0))
                .setParameter("id", slice.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return slice;
    }

    private User buyer(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("x");
        user.setFirstName("A");
        user.setLastName("Buyer");
        user.setRole(UserRole.CUSTOMER);
        return users.save(user);
    }

    // ── Currency isolation ───────────────────────────────────────────────────

    @Test
    void revenueInTwoCurrenciesIsReportedTwiceAndNeverAdded() {
        sale("SJL-A-1", TODAY.minusDays(2), "GMD", "9700.00", "970.00", 1, "GM", buyer("a@x.gm"));
        sale("SJL-A-2", TODAY.minusDays(2), "XOF", "14500", "1450", 1, "SN", buyer("b@x.gm"));

        AnalyticsResponses.Overview overview =
                analytics.overview(sellerUser.getId(), MONTH_START, TODAY.plusDays(1));

        assertEquals(2, overview.byCurrency().size());
        // Two figures, and nowhere a third that added them: no single rate is
        // true of both these orders (C2).
        assertEquals(0, money(overview, "GMD").revenue().compareTo(new BigDecimal("9700.00")));
        assertEquals(0, money(overview, "XOF").revenue().compareTo(new BigDecimal("14500")));
        assertNotNull(overview.note(), "and the response says why they are apart");
    }

    @Test
    void countsAreAcrossCurrenciesBecauseACountIsNotMoney() {
        sale("SJL-A-3", TODAY.minusDays(2), "GMD", "9700.00", "970.00", 2, "GM", buyer("c@x.gm"));
        sale("SJL-A-4", TODAY.minusDays(2), "XOF", "14500", "1450", 3, "SN", buyer("d@x.gm"));

        AnalyticsResponses.Overview overview =
                analytics.overview(sellerUser.getId(), MONTH_START, TODAY.plusDays(1));

        // Two orders is two orders whatever they were priced in.
        assertEquals(2, overview.orders());
        assertEquals(5, overview.unitsSold());
    }

    @Test
    void eachSalesSeriesCarriesItsOwnCurrency() {
        sale("SJL-A-5", TODAY.minusDays(1), "GMD", "9700.00", "970.00", 1, "GM", buyer("e@x.gm"));
        sale("SJL-A-6", TODAY.minusDays(1), "XOF", "14500", "1450", 1, "SN", buyer("f@x.gm"));

        AnalyticsResponses.Sales sales =
                analytics.sales(sellerUser.getId(), TODAY.minusDays(6), TODAY.plusDays(1), "day");

        assertEquals(2, sales.byCurrency().size());
        assertTrue(sales.byCurrency().stream().anyMatch(s -> s.currency().equals("GMD")));
        assertTrue(sales.byCurrency().stream().anyMatch(s -> s.currency().equals("XOF")));
    }

    // ── Windows ──────────────────────────────────────────────────────────────

    @Test
    void theWindowIncludesItsStartAndExcludesItsEnd() {
        sale("SJL-A-7", LocalDate.of(2026, 9, 10), "GMD", "1000.00", "100.00", 1, "GM", buyer("g@x.gm"));
        sale("SJL-A-8", LocalDate.of(2026, 9, 12), "GMD", "2000.00", "200.00", 1, "GM", buyer("h@x.gm"));

        // 10th to 12th: the 10th is in, the 12th is not. Anything else
        // double-counts or drops a day when two periods sit side by side.
        AnalyticsResponses.Overview overview = analytics.overview(
                sellerUser.getId(), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12));

        assertEquals(1, overview.orders());
        assertEquals(0, money(overview, "GMD").revenue().compareTo(new BigDecimal("1000.00")));
    }

    @Test
    void theComparisonPeriodIsTheSameLengthImmediatelyBefore() {
        AnalyticsResponses.Overview overview = analytics.overview(
                sellerUser.getId(), LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 14));

        assertEquals(3, overview.period().days());
        assertEquals(3, overview.comparedWith().days());
        assertEquals(LocalDate.of(2026, 9, 8), overview.comparedWith().from());
        assertEquals(LocalDate.of(2026, 9, 11), overview.comparedWith().to());
    }

    @Test
    void aSeriesHasABucketForEveryDayIncludingTheEmptyOnes() {
        sale("SJL-A-9", TODAY.minusDays(1), "GMD", "1000.00", "100.00", 1, "GM", buyer("i@x.gm"));

        AnalyticsResponses.Sales sales =
                analytics.sales(sellerUser.getId(), TODAY.minusDays(6), TODAY.plusDays(1), "day");

        // Seven days asked for, seven points back. A chart drawn from a sparse
        // series closes the gaps and makes a dead fortnight look like a slow week.
        assertEquals(7, sales.byCurrency().get(0).points().size());
        assertEquals(6, sales.byCurrency().get(0).points().stream()
                .filter(p -> p.revenue().signum() == 0).count());
    }

    @Test
    void weeklyAndMonthlyGroupingsFoldTheSameDaysDifferently() {
        sale("SJL-A-10", LocalDate.of(2026, 9, 2), "GMD", "1000.00", "100.00", 1, "GM", buyer("j@x.gm"));
        sale("SJL-A-11", LocalDate.of(2026, 9, 20), "GMD", "2000.00", "200.00", 1, "GM", buyer("k@x.gm"));

        AnalyticsResponses.Sales weekly = analytics.sales(
                sellerUser.getId(), MONTH_START, LocalDate.of(2026, 10, 1), "week");
        AnalyticsResponses.Sales monthly = analytics.sales(
                sellerUser.getId(), MONTH_START, LocalDate.of(2026, 10, 1), "month");

        assertEquals(1, monthly.byCurrency().get(0).points().size());
        assertTrue(weekly.byCurrency().get(0).points().size() >= 4);
        // However they are folded, the totals are the same money.
        assertEquals(0, monthly.byCurrency().get(0).total()
                .compareTo(weekly.byCurrency().get(0).total()));
    }

    @Test
    void anImpossibleOrEnormousWindowIsRefusedRatherThanTruncated() {
        assertThrows(BadRequestException.class,
                () -> analytics.overview(sellerUser.getId(), TODAY, TODAY));
        assertThrows(BadRequestException.class,
                () -> analytics.overview(sellerUser.getId(), TODAY.minusYears(5), TODAY));
        assertThrows(BadRequestException.class,
                () -> analytics.sales(sellerUser.getId(), MONTH_START, TODAY, "fortnight"));
    }

    // ── Percentages that do not lie ──────────────────────────────────────────

    @Test
    void growthFromNothingIsNotAPercentage() {
        sale("SJL-A-12", TODAY.minusDays(1), "GMD", "1000.00", "100.00", 1, "GM", buyer("l@x.gm"));

        AnalyticsResponses.Overview overview =
                analytics.overview(sellerUser.getId(), TODAY.minusDays(3), TODAY.plusDays(1));

        // Nothing sold in the period before. A first sale is not an increase of
        // any percentage, and rendering one invites a seller to read it as growth.
        assertNull(money(overview, "GMD").revenueChangePercent());
    }

    @Test
    void conversionIsNullWithNoViewsRatherThanZero() {
        sale("SJL-A-13", TODAY.minusDays(1), "GMD", "1000.00", "100.00", 1, "GM", buyer("m@x.gm"));

        AnalyticsResponses.Overview overview =
                analytics.overview(sellerUser.getId(), TODAY.minusDays(3), TODAY.plusDays(1));

        assertNull(overview.conversionPercent(), "no views is unknown, not zero percent");
    }

    @Test
    void theFunnelSaysWhatItsViewsActuallyCount() {
        views.save(ProductViewStat.builder()
                .product(kettle).vendor(vendor).viewedOn(TODAY.minusDays(1)).views(100L).build());
        sale("SJL-A-14", TODAY.minusDays(1), "GMD", "1000.00", "100.00", 1, "GM", buyer("n@x.gm"));
        entityManager.flush();

        AnalyticsResponses.Products report =
                analytics.products(sellerUser.getId(), TODAY.minusDays(3), TODAY.plusDays(1), 20);

        assertEquals(100, report.funnel().views());
        assertEquals(1, report.funnel().ordersContainingAProduct());
        assertEquals(1.0, report.funnel().viewToOrderPercent());
        // A conversion rate that quietly means something other than what a
        // seller assumes is worse than no conversion rate.
        assertTrue(report.funnel().basis().contains("not unique visitors"));
    }

    // ── Customers, without customers ─────────────────────────────────────────

    @Test
    void aReturningBuyerIsCountedAsReturningNotNew() {
        User regular = buyer("regular@x.gm");
        sale("SJL-A-15", LocalDate.of(2026, 8, 1), "GMD", "1000.00", "100.00", 1, "GM", regular);
        sale("SJL-A-16", LocalDate.of(2026, 9, 10), "GMD", "1000.00", "100.00", 1, "GM", regular);

        AnalyticsResponses.Customers customers = analytics.customers(
                sellerUser.getId(), MONTH_START, LocalDate.of(2026, 9, 30));

        assertEquals(1, customers.buyers());
        assertEquals(0, customers.newBuyers(), "their first order was in August");
        assertEquals(1, customers.returningBuyers());
    }

    @Test
    void aFirstTimeBuyerInTheWindowIsNew() {
        sale("SJL-A-17", LocalDate.of(2026, 9, 10), "GMD", "1000.00", "100.00", 1, "GM",
                buyer("fresh@x.gm"));

        AnalyticsResponses.Customers customers = analytics.customers(
                sellerUser.getId(), MONTH_START, LocalDate.of(2026, 9, 30));

        assertEquals(1, customers.newBuyers());
        assertEquals(0, customers.returningBuyers());
    }

    @Test
    void aDestinationWithTooFewOrdersIsSuppressedEntirely() {
        // Six to Gambia, one to Sweden.
        for (int i = 0; i < 6; i++) {
            sale("SJL-A-GM-" + i, LocalDate.of(2026, 9, 10), "GMD", "1000.00", "100.00", 1, "GM",
                    buyer("gm" + i + "@x.gm"));
        }
        sale("SJL-A-SE-1", LocalDate.of(2026, 9, 10), "GMD", "1000.00", "100.00", 1, "SE",
                buyer("se@x.gm"));

        AnalyticsResponses.Customers customers = analytics.customers(
                sellerUser.getId(), MONTH_START, LocalDate.of(2026, 9, 30));

        assertTrue(customers.destinations().stream().anyMatch(d -> d.country().equals("GM")));
        // One order to an unusual country, beside a page saying what was in it,
        // names the person who placed it.
        assertFalse(customers.destinations().stream().anyMatch(d -> d.country().equals("SE")));
        assertEquals(1, customers.suppressedDestinations());
        assertTrue(customers.privacyNote().contains("identifies"));
    }

    @Test
    void nothingInTheCustomerViewIdentifiesAnybody() {
        sale("SJL-A-18", LocalDate.of(2026, 9, 10), "GMD", "1000.00", "100.00", 1, "GM",
                buyer("isatou.ceesay@example.gm"));

        String rendered = analytics.customers(
                sellerUser.getId(), MONTH_START, LocalDate.of(2026, 9, 30)).toString();

        assertFalse(rendered.contains("isatou"), "no email");
        assertFalse(rendered.contains("Serrekunda"), "no town, only a country");
        assertFalse(rendered.contains("SJL-A-18"), "no order numbers");
    }

    // ── Delivery ─────────────────────────────────────────────────────────────

    @Test
    void timeToReadyIsMeasuredFromAcceptance() {
        sale("SJL-A-19", LocalDate.of(2026, 9, 10), "GMD", "1000.00", "100.00", 1, "GM",
                buyer("o@x.gm"));

        AnalyticsResponses.Delivery delivery = analytics.delivery(
                sellerUser.getId(), MONTH_START, LocalDate.of(2026, 9, 30));

        // Accepted 09:00, packed 13:00.
        assertEquals(4.0, delivery.averageHoursToReady());
    }

    @Test
    void aPeriodWithNoParcelsSaysSoRatherThanShowingZeroPercent() {
        AnalyticsResponses.Delivery delivery = analytics.delivery(
                sellerUser.getId(), MONTH_START, LocalDate.of(2026, 9, 30));

        assertEquals(0, delivery.parcels());
        assertNull(delivery.successPercent(), "a rate over nothing is not zero");
        assertNotNull(delivery.note());
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    @Test
    void aUserWithNoShopHasNoFigures() {
        User shopper = buyer("shopper@x.gm");
        assertThrows(com.sujula.exceptions.ResourceNotFoundException.class,
                () -> analytics.overview(shopper.getId(), MONTH_START, TODAY));
    }

    private static AnalyticsResponses.OverviewMoney money(AnalyticsResponses.Overview overview,
                                                          String currency) {
        return overview.byCurrency().stream()
                .filter(m -> m.currency().equals(currency))
                .findFirst().orElseThrow(() -> new AssertionError("no " + currency + " figures"));
    }
}
