package com.sujula.config.seed;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.sujula.model.Address;
import com.sujula.model.Review;
import com.sujula.model.aftersales.Dispute;
import com.sujula.model.aftersales.ReturnRequest;
import com.sujula.model.delivery.Delivery;
import com.sujula.model.delivery.Driver;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.logistics.DeliveryZone;
import com.sujula.model.order.Cart;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Brand;
import com.sujula.model.products.Category;
import com.sujula.model.products.Coupon;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

import jakarta.persistence.EntityManager;

/**
 * The rows built so far, and the means to build more.
 *
 * <p>Sample data is a graph, not a list: a custody event belongs to a leg of a
 * shipment of a vendor order of an order for a product a vendor listed. Each
 * stage therefore needs to name what earlier stages made, and naming it by
 * database id would mean either flushing and reading back or hard-coding
 * identifiers the database is free to choose differently. So stages hand
 * entities forward here under short keys, and later stages ask for them by name:
 * {@code cat.user("isatou")} rather than {@code userRepository.findById(7L)}.
 *
 * <p>The counters exist so the startup log can state what was written. A seeder
 * that says "done" tells you nothing about whether it wrote three rows or three
 * hundred.
 */
public class SeedCatalogue {

    /**
     * The password every sampled account has.
     *
     * <p>Published on purpose, and the reason {@code SampleDataSeeder} is off by
     * default: a dataset whose accounts nobody can sign in to cannot be used to
     * exercise anything, and a dataset whose accounts everybody can sign in to
     * has no business in a deployment.
     */
    public static final String SAMPLE_PASSWORD = "Password123!";

    private final EntityManager em;
    private final PasswordEncoder passwordEncoder;

    /**
     * One instant for the whole dataset.
     *
     * <p>Every relative date below is expressed against it, so an order placed
     * "eight days ago" is eight days before the shipment that carried it however
     * long the seeder itself takes to run.
     */
    public final LocalDateTime now;

    private int rows;
    private final Set<String> entities = new LinkedHashSet<>();

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Vendor> vendors = new LinkedHashMap<>();
    public final Map<String, Address> addresses = new LinkedHashMap<>();
    public final Map<String, Brand> brands = new LinkedHashMap<>();
    public final Map<String, Category> categories = new LinkedHashMap<>();
    public final Map<String, Product> products = new LinkedHashMap<>();
    public final Map<String, ProductVariant> variants = new LinkedHashMap<>();
    public final Map<String, Review> reviews = new LinkedHashMap<>();
    public final Map<String, Coupon> coupons = new LinkedHashMap<>();
    public final Map<String, DeliveryZone> zones = new LinkedHashMap<>();
    public final Map<String, PickupPoint> pickupPoints = new LinkedHashMap<>();
    public final Map<String, Driver> drivers = new LinkedHashMap<>();
    public final Map<String, Cart> carts = new LinkedHashMap<>();
    public final Map<String, Order> orders = new LinkedHashMap<>();
    public final Map<String, VendorOrder> vendorOrders = new LinkedHashMap<>();
    public final Map<String, OrderItem> orderItems = new LinkedHashMap<>();
    public final Map<String, Shipment> shipments = new LinkedHashMap<>();
    public final Map<String, Delivery> deliveries = new LinkedHashMap<>();
    public final Map<String, ReturnRequest> returns = new LinkedHashMap<>();
    public final Map<String, Dispute> disputes = new LinkedHashMap<>();

    SeedCatalogue(EntityManager em, PasswordEncoder passwordEncoder, LocalDateTime now) {
        this.em = em;
        this.passwordEncoder = passwordEncoder;
        this.now = now;
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /** Persists one row and counts it. Returns the entity so calls can chain. */
    public <T> T save(T entity) {
        em.persist(entity);
        entities.add(entity.getClass().getSimpleName());
        rows++;
        return entity;
    }

    /**
     * Sends everything written so far to the database.
     *
     * <p>Called between stages rather than only at the end. Identity ids are
     * assigned on insert, and a later stage that reads {@code getId()} off an
     * entity the persistence context has not flushed gets null — which then
     * lands in a nullable column and is found weeks later.
     */
    public void flush() {
        em.flush();
    }

    /** The one password every sampled account is given, already hashed. */
    public String samplePasswordHash() {
        return passwordEncoder.encode(SAMPLE_PASSWORD);
    }

    public int rows() {
        return rows;
    }

    public int entityCount() {
        return entities.size();
    }

    // ── Reading back ─────────────────────────────────────────────────────────

    public User user(String key) {
        return required(users, key, "user");
    }

    public Vendor vendor(String key) {
        return required(vendors, key, "vendor");
    }

    public Address address(String key) {
        return required(addresses, key, "address");
    }

    public Brand brand(String key) {
        return required(brands, key, "brand");
    }

    public Category category(String key) {
        return required(categories, key, "category");
    }

    public Product product(String key) {
        return required(products, key, "product");
    }

    public ProductVariant variant(String key) {
        return required(variants, key, "variant");
    }

    public Review review(String key) {
        return required(reviews, key, "review");
    }

    public Coupon coupon(String key) {
        return required(coupons, key, "coupon");
    }

    public DeliveryZone zone(String key) {
        return required(zones, key, "delivery zone");
    }

    public PickupPoint pickupPoint(String key) {
        return required(pickupPoints, key, "pickup point");
    }

    public Driver driver(String key) {
        return required(drivers, key, "driver");
    }

    public Cart cart(String key) {
        return required(carts, key, "cart");
    }

    public Order order(String key) {
        return required(orders, key, "order");
    }

    public VendorOrder vendorOrder(String key) {
        return required(vendorOrders, key, "vendor order");
    }

    public OrderItem orderItem(String key) {
        return required(orderItems, key, "order item");
    }

    public Shipment shipment(String key) {
        return required(shipments, key, "shipment");
    }

    public Delivery delivery(String key) {
        return required(deliveries, key, "delivery");
    }

    public ReturnRequest returnRequest(String key) {
        return required(returns, key, "return request");
    }

    public Dispute dispute(String key) {
        return required(disputes, key, "dispute");
    }

    private static <T> T required(Map<String, T> from, String key, String what) {
        T found = from.get(key);
        if (found == null) {
            throw new IllegalStateException(
                    "Sample data asked for the " + what + " '" + key + "' before any stage created it. "
                            + "Known keys: " + from.keySet());
        }
        return found;
    }

    // ── Small conveniences the stages all want ───────────────────────────────

    public LocalDateTime daysAgo(long days) {
        return now.minusDays(days);
    }

    public LocalDateTime hoursAgo(long hours) {
        return now.minusHours(hours);
    }

    public LocalDateTime daysAhead(long days) {
        return now.plusDays(days);
    }

    public LocalDateTime hoursAhead(long hours) {
        return now.plusHours(hours);
    }

    /** Money, at two decimal places, which is right for every currency here but XOF. */
    public static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Money in a currency with no minor unit.
     *
     * <p>XOF has no centime, so a CFA figure carrying decimals is an amount that
     * does not exist. Kept separate from {@link #money} so the difference is
     * visible at the call site rather than hidden in a rounding mode.
     */
    public static BigDecimal wholeUnits(String amount) {
        return new BigDecimal(amount).setScale(0, java.math.RoundingMode.HALF_UP);
    }

    /** An FX rate, at the eight places a rate is stored with. */
    public static BigDecimal rate(String value) {
        return new BigDecimal(value).setScale(8, java.math.RoundingMode.HALF_UP);
    }
}
