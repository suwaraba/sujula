package com.sujula.config.seed;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartCoupon;
import com.sujula.model.order.CartItem;
import com.sujula.model.order.CartQuote;
import com.sujula.model.order.CartQuoteLine;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.OrderStatusHistory;
import com.sujula.model.order.Payment;
import com.sujula.model.order.RefundRequest;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.CouponUsage;
import com.sujula.model.user.Vendor;

/**
 * Baskets, quotes, orders, the money taken for them, and refunds.
 *
 * <p>Eleven orders, and between them every {@code OrderStatus}, every
 * {@code PaymentStatus}, every {@code PaymentMethod} and every
 * {@code VendorOrderStatus} this system defines. That is not completeness for
 * its own sake: each of those is a branch somebody's code takes, and a dataset
 * containing only paid, delivered orders exercises one of them.
 *
 * <p>Three things here are the marketplace's own shape rather than generic
 * commerce:
 *
 * <ul>
 *   <li><b>SJL-1002 pays one payment to two sellers in two currencies</b> — a
 *       Banjul store settling in dalasi and a Dakar store settling in CFA, from
 *       one card charge in euro. The two sub-orders ship, cancel and pay out
 *       independently (C3).</li>
 *   <li><b>Every converted figure carries the rate it was converted at</b>, on
 *       the vendor order and on each line. Nothing here recomputes (C2).</li>
 *   <li><b>The payer and the recipient are different people</b> on most of
 *       them. Isatou pays from Madrid; her sister signs in Serrekunda (C1).</li>
 * </ul>
 */
@Component
class CommerceStage implements SeedStage {

    // The rates these orders were priced at. Fixed constants rather than reads
    // of the rate table: an order carries the rate it was priced at, and a
    // seeder that looked today's rate up would be doing the exact thing C2
    // forbids.
    private static final BigDecimal GMD_EUR = SeedCatalogue.rate("0.01341000");
    private static final BigDecimal GMD_GBP = SeedCatalogue.rate("0.01139000");
    private static final BigDecimal GMD_SEK = SeedCatalogue.rate("0.15540000");
    private static final BigDecimal XOF_EUR = SeedCatalogue.rate("0.00151800");

    @Override
    public String name() {
        return "Carts, quotes, orders, payments and refunds";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        carts(cat);
        cat.flush();
        quotes(cat);
        orders(cat);
        cat.flush();
        couponUsages(cat);
        refundRequests(cat);
    }

    // ── Carts ────────────────────────────────────────────────────────────────

    private void carts(SeedCatalogue cat) {
        // A signed-in buyer, two sellers in one basket, a platform coupon on it.
        Cart isatou = cat.save(Cart.builder()
                .user(cat.user("isatou")).displayCurrency("EUR")
                .token("cart-token-sample-isatou-0001")
                .deliveryContextId("dctx-sample-isatou-serrekunda-0001")
                .expiresAt(cat.daysAhead(30))
                .build());
        cat.carts.put("isatou", isatou);
        cat.save(CartItem.builder().cart(isatou)
                .product(cat.product("spark10")).variant(cat.variant("spark10-128-black"))
                .vendor(cat.vendor("banjul-phones"))
                .quantity(1).unitPrice(SeedCatalogue.money("8500.00")).unitPriceCurrency("GMD")
                .priceCheckedAt(cat.hoursAgo(2))
                .build());
        cat.save(CartItem.builder().cart(isatou)
                .product(cat.product("castironpot"))
                .vendor(cat.vendor("serrekunda-home"))
                .quantity(2).unitPrice(SeedCatalogue.money("1450.00")).unitPriceCurrency("GMD")
                .priceCheckedAt(cat.hoursAgo(2))
                .build());
        cat.save(CartCoupon.builder().cart(isatou).coupon(cat.coupon("diaspora500")).build());
        // A second coupon on the same cart, scoped to one seller. The unique key
        // is (cart, vendor), so a platform coupon and a vendor coupon coexist —
        // which is the arrangement most likely to be got wrong.
        cat.save(CartCoupon.builder().cart(isatou)
                .coupon(cat.coupon("bp15")).vendor(cat.vendor("banjul-phones")).build());

        // A guest. No account, a session id and a token — C5's shape again: not
        // everybody in this system has signed up for anything.
        Cart guest = cat.save(Cart.builder()
                .sessionId("9f3c21ab-77de-4a10-9b2e-sample000001")
                .token("cart-token-sample-guest-0002")
                .displayCurrency("GMD")
                .deliveryContextId("dctx-sample-anonymous-serrekunda-0005")
                .expiresAt(cat.daysAhead(7))
                .build());
        cat.carts.put("guest", guest);
        cat.save(CartItem.builder().cart(guest)
                .product(cat.product("powerbank"))
                .vendor(cat.vendor("dakar-tech"))
                .quantity(1).unitPrice(SeedCatalogue.wholeUnits("12500")).unitPriceCurrency("XOF")
                .priceCheckedAt(cat.hoursAgo(1))
                .build());

        Cart modou = cat.save(Cart.builder()
                .user(cat.user("modou")).displayCurrency("GBP")
                .token("cart-token-sample-modou-0003")
                .deliveryContextId("dctx-sample-modou-brikama-0002")
                .expiresAt(cat.daysAhead(30))
                .build());
        cat.carts.put("modou", modou);
        cat.save(CartItem.builder().cart(modou)
                .product(cat.product("hot30"))
                .vendor(cat.vendor("banjul-phones"))
                .quantity(2).unitPrice(SeedCatalogue.money("9750.00")).unitPriceCurrency("GMD")
                .priceCheckedAt(cat.daysAgo(3))
                .build());

        // Empty. The first thing the cart page has to render, and the easiest
        // to forget.
        cat.carts.put("binta", cat.save(Cart.builder()
                .user(cat.user("binta")).displayCurrency("GMD")
                .token("cart-token-sample-binta-0004")
                .expiresAt(cat.daysAhead(30))
                .build()));

        // Abandoned and past its expiry. The cleanup job's input.
        Cart stale = cat.save(Cart.builder()
                .user(cat.user("sally")).displayCurrency("EUR")
                .token("cart-token-sample-sally-0005")
                .expiresAt(cat.daysAgo(3))
                .build());
        cat.carts.put("sally", stale);
        cat.save(CartItem.builder().cart(stale)
                .product(cat.product("waxfabric")).variant(cat.variant("wax-6"))
                .vendor(cat.vendor("kololi-style"))
                .quantity(1).unitPrice(SeedCatalogue.money("1850.00")).unitPriceCurrency("GMD")
                .priceCheckedAt(cat.daysAgo(30))
                .build());

        Cart cheikh = cat.save(Cart.builder()
                .user(cat.user("cheikh")).displayCurrency("XOF")
                .token("cart-token-sample-cheikh-0006")
                .deliveryContextId("dctx-sample-cheikh-dakar-0004")
                .expiresAt(cat.daysAhead(30))
                .build());
        cat.carts.put("cheikh", cheikh);
        cat.save(CartItem.builder().cart(cheikh)
                .product(cat.product("galaxya15")).variant(cat.variant("a15-bleu"))
                .vendor(cat.vendor("dakar-tech"))
                .quantity(1).unitPrice(SeedCatalogue.wholeUnits("95000")).unitPriceCurrency("XOF")
                .priceCheckedAt(cat.hoursAgo(5))
                .build());
        cat.save(CartCoupon.builder().cart(cheikh)
                .coupon(cat.coupon("dt5000")).vendor(cat.vendor("dakar-tech")).build());
        // A platform coupon on the same basket, alongside the seller's own.
        cat.save(CartCoupon.builder().cart(cheikh).coupon(cat.coupon("openended")).build());

        // A coupon held on a basket that has already gone stale. It has to stop
        // applying because the cart expired, not because the coupon did.
        cat.save(CartCoupon.builder().cart(stale).coupon(cat.coupon("expired")).build());

        // On the guest's basket. A coupon does not require an account either.
        cat.save(CartCoupon.builder().cart(guest).coupon(cat.coupon("freeship")).build());
    }

    // ── Held quotes ──────────────────────────────────────────────────────────

    /**
     * A basket priced and frozen, ready for checkout.
     *
     * <p>The interesting rows are the ones that cannot be checked out: an
     * expired quote, a quote already spent on an order, and an incomplete one
     * whose lines say why. An incomplete quote with no reason on the line is a
     * checkout that fails with nothing to tell the buyer.
     */
    private void quotes(SeedCatalogue cat) {
        CartQuote live = cat.save(CartQuote.builder()
                .id("cq-sample-isatou-live-0001")
                .cart(cat.cart("isatou")).user(cat.user("isatou"))
                .cartFingerprint("fp-isatou-2items-spark10-pot-0001")
                .displayCurrency("EUR")
                .deliveryContextId("dctx-sample-isatou-serrekunda-0001")
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(SeedCatalogue.money("152.87"))
                .discount(SeedCatalogue.money("17.10"))
                .shipping(SeedCatalogue.money("4.83"))
                .tax(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("140.60"))
                .complete(true)
                .createdAt(cat.hoursAgo(1)).expiresAt(cat.hoursAhead(1))
                .build());
        cat.save(CartQuoteLine.builder().quote(live)
                .productId(cat.product("spark10").getId())
                .variantId(cat.variant("spark10-128-black").getId())
                .vendorId(cat.vendor("banjul-phones").getId())
                .quantity(1).listingCurrency("GMD")
                .unitPriceNative(SeedCatalogue.money("8500.00"))
                .lineTotalNative(SeedCatalogue.money("8500.00"))
                .unitPrice(SeedCatalogue.money("113.99")).lineTotal(SeedCatalogue.money("113.99"))
                .deliveryCost(SeedCatalogue.money("2.82"))
                .distanceKm(new BigDecimal("9.400")).billableWeightKg(new BigDecimal("0.420"))
                .deliverable(true)
                .fx(FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.hoursAgo(1)))
                .build());
        cat.save(CartQuoteLine.builder().quote(live)
                .productId(cat.product("castironpot").getId())
                .vendorId(cat.vendor("serrekunda-home").getId())
                .quantity(2).listingCurrency("GMD")
                .unitPriceNative(SeedCatalogue.money("1450.00"))
                .lineTotalNative(SeedCatalogue.money("2900.00"))
                .unitPrice(SeedCatalogue.money("19.44")).lineTotal(SeedCatalogue.money("38.88"))
                .deliveryCost(SeedCatalogue.money("2.01"))
                .distanceKm(new BigDecimal("1.900")).billableWeightKg(new BigDecimal("12.800"))
                .deliverable(true)
                .fx(FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.hoursAgo(1)))
                .build());

        // Spent. It became SJL-1001, and it must never be spendable again.
        cat.save(CartQuote.builder()
                .id("cq-sample-isatou-consumed-0002")
                .cart(cat.cart("isatou")).user(cat.user("isatou"))
                .cartFingerprint("fp-isatou-1item-spark10-0002")
                .displayCurrency("EUR")
                .deliveryContextId("dctx-sample-isatou-serrekunda-0001")
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(SeedCatalogue.money("113.99")).discount(SeedCatalogue.money("0.00"))
                .shipping(SeedCatalogue.money("2.82")).tax(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("116.81"))
                .complete(true)
                .createdAt(cat.daysAgo(12)).expiresAt(cat.daysAgo(12).plusMinutes(15))
                .consumedAt(cat.daysAgo(12).plusMinutes(3))
                .build());

        cat.save(CartQuote.builder()
                .id("cq-sample-modou-expired-0003")
                .cart(cat.cart("modou")).user(cat.user("modou"))
                .cartFingerprint("fp-modou-2x-hot30-0003")
                .displayCurrency("GBP")
                .deliveryContextId("dctx-sample-modou-brikama-0002")
                .deliveryMode(DeliveryMode.PICKUP_POINT)
                .pickupPointId(cat.pickupPoint("brikama").getId())
                .subtotal(SeedCatalogue.money("222.11")).discount(SeedCatalogue.money("0.00"))
                .shipping(SeedCatalogue.money("1.14")).tax(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("223.25"))
                .complete(true)
                .createdAt(cat.daysAgo(3)).expiresAt(cat.daysAgo(3).plusMinutes(15))
                .build());

        // Incomplete: one line cannot be delivered where it is going. The quote
        // still prices what it can and names the obstacle rather than refusing
        // the whole basket with nothing said.
        CartQuote incomplete = cat.save(CartQuote.builder()
                .id("cq-sample-sally-incomplete-0004")
                .cart(cat.cart("sally")).user(cat.user("sally"))
                .cartFingerprint("fp-sally-wax-0004")
                .displayCurrency("EUR")
                .deliveryContextId("dctx-sample-northbank-0008")
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(SeedCatalogue.money("24.81")).discount(SeedCatalogue.money("0.00"))
                .shipping(SeedCatalogue.money("0.00")).tax(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("24.81"))
                .complete(false)
                .createdAt(cat.hoursAgo(2)).expiresAt(cat.hoursAhead(1))
                .build());
        cat.save(CartQuoteLine.builder().quote(incomplete)
                .productId(cat.product("waxfabric").getId())
                .variantId(cat.variant("wax-6").getId())
                .vendorId(cat.vendor("kololi-style").getId())
                .quantity(1).listingCurrency("GMD")
                .unitPriceNative(SeedCatalogue.money("1850.00"))
                .lineTotalNative(SeedCatalogue.money("1850.00"))
                .unitPrice(SeedCatalogue.money("24.81")).lineTotal(SeedCatalogue.money("24.81"))
                .deliveryCost(SeedCatalogue.money("0.00"))
                .deliverable(false)
                .issue("No driver covers the north bank. Choose a pickup point instead.")
                .fx(FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.hoursAgo(2)))
                .build());

        // A guest's quote. No user on it at all.
        CartQuote anonymous = cat.save(CartQuote.builder()
                .id("cq-sample-guest-0005")
                .cart(cat.cart("guest"))
                .cartFingerprint("fp-guest-powerbank-0005")
                .displayCurrency("GMD")
                .deliveryContextId("dctx-sample-anonymous-serrekunda-0005")
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(SeedCatalogue.money("1411.00")).discount(SeedCatalogue.money("0.00"))
                .shipping(SeedCatalogue.money("150.00")).tax(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("1561.00"))
                .complete(true)
                .createdAt(cat.hoursAgo(1)).expiresAt(cat.hoursAhead(1))
                .build());
        cat.save(CartQuoteLine.builder().quote(anonymous)
                .productId(cat.product("powerbank").getId())
                .vendorId(cat.vendor("dakar-tech").getId())
                .quantity(1).listingCurrency("XOF")
                .unitPriceNative(SeedCatalogue.wholeUnits("12500"))
                .lineTotalNative(SeedCatalogue.wholeUnits("12500"))
                .unitPrice(SeedCatalogue.money("1411.00")).lineTotal(SeedCatalogue.money("1411.00"))
                .deliveryCost(SeedCatalogue.money("150.00"))
                .distanceKm(new BigDecimal("214.600")).billableWeightKg(new BigDecimal("0.350"))
                .deliverable(true)
                .fx(FxSnapshot.published("XOF", "GMD", SeedCatalogue.rate("0.11287000"),
                        cat.hoursAgo(1)))
                .build());

        // Same currency both sides, so the snapshot records an identity rather
        // than being left null. "Nothing was converted" is a fact, not an
        // absence.
        CartQuote identity = cat.save(CartQuote.builder()
                .id("cq-sample-cheikh-identity-0006")
                .cart(cat.cart("cheikh")).user(cat.user("cheikh"))
                .cartFingerprint("fp-cheikh-a15-0006")
                .displayCurrency("XOF")
                .deliveryContextId("dctx-sample-cheikh-dakar-0004")
                .deliveryMode(DeliveryMode.VENDOR_PICKUP)
                .subtotal(SeedCatalogue.wholeUnits("95000"))
                .discount(SeedCatalogue.wholeUnits("5000"))
                .shipping(SeedCatalogue.wholeUnits("0"))
                .tax(SeedCatalogue.wholeUnits("0"))
                .total(SeedCatalogue.wholeUnits("90000"))
                .complete(true)
                .createdAt(cat.hoursAgo(1)).expiresAt(cat.hoursAhead(1))
                .build());
        cat.save(CartQuoteLine.builder().quote(identity)
                .productId(cat.product("galaxya15").getId())
                .variantId(cat.variant("a15-bleu").getId())
                .vendorId(cat.vendor("dakar-tech").getId())
                .quantity(1).listingCurrency("XOF")
                .unitPriceNative(SeedCatalogue.wholeUnits("95000"))
                .lineTotalNative(SeedCatalogue.wholeUnits("95000"))
                .unitPrice(SeedCatalogue.wholeUnits("95000"))
                .lineTotal(SeedCatalogue.wholeUnits("95000"))
                .deliveryCost(SeedCatalogue.wholeUnits("0"))
                .deliverable(true)
                .fx(FxSnapshot.identity("XOF", cat.hoursAgo(1)))
                .build());
    }

    // ── Orders ───────────────────────────────────────────────────────────────

    private void orders(SeedCatalogue cat) {
        sjl1001(cat);
        sjl1002(cat);
        sjl1003(cat);
        sjl1004(cat);
        sjl1005(cat);
        sjl1006(cat);
        sjl1007(cat);
        sjl1008(cat);
        sjl1009(cat);
        sjl1010(cat);
        sjl1011(cat);
    }

    /** Madrid pays, Serrekunda receives, delivered and settled. */
    private void sjl1001(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(12);
        Order order = Order.builder()
                .orderNumber("SJL-1001").trackingCode("TRK-SJL-1001")
                .customer(cat.user("isatou"))
                .status(OrderStatus.DELIVERED)
                .subtotal(SeedCatalogue.money("113.99"))
                .shippingCost(SeedCatalogue.money("2.82"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("116.81"))
                .currency("EUR")
                .shippingFullName("Aji Ceesay").shippingPhone("+2207700100")
                .shippingStreet("Sayerr Jobe Avenue, near Westfield")
                .shippingCity("Serrekunda").shippingState("Kanifing").shippingCountry("GM")
                .shippingLatitude(13.4383).shippingLongitude(-16.6781)
                .shippingAddress(cat.address("isatou-serrekunda"))
                .billingFullName("Isatou Ceesay").billingStreet("Calle de Bravo Murillo 145")
                .billingCity("Madrid").billingPostalCode("28020").billingCountry("ES")
                .paymentStatus(PaymentStatus.PAID).paymentMethod(PaymentMethod.CARD)
                .paidAt(placed.plusMinutes(2))
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .deliveryInstructions("Ring the bell twice. My sister may be at the back.")
                .loyaltyPointsEarned(116L)
                .build();
        cat.orders.put("1001", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1001-bp", order, cat.vendor("banjul-phones"),
                VendorOrderStatus.DELIVERED, "GMD",
                "8500.00", "0.00", "8500.00",
                "113.99", "0.00", "113.99",
                "8.00", "680.00", "210.00", "7820.00",
                FxSnapshot.held("GMD", "EUR", GMD_EUR, placed, "fxq-sample-isatou-consumed-0002"));
        vendorOrder.setAcceptedAt(placed.plusHours(3));
        vendorOrder.setReadyAt(cat.daysAgo(11));
        vendorOrder.setCollectedAt(cat.daysAgo(10));
        vendorOrder.setReceiptConfirmedAt(cat.daysAgo(9));
        // Money leaves escrow because delivery was proven, not because a status
        // was set (C4).
        vendorOrder.setEscrowReleasedAt(cat.daysAgo(9).plusHours(1));
        vendorOrder.setReleaseCodeIssueCount(1);
        vendorOrder.setReleaseCodeIssuedAt(cat.daysAgo(10).minusHours(1));

        cat.orderItems.put("1001-spark10", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("spark10")).variant(cat.variant("spark10-128-black"))
                .vendor(cat.vendor("banjul-phones"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("8500.00"))
                .totalPrice(SeedCatalogue.money("8500.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("113.99"))
                .totalPriceConverted(SeedCatalogue.money("113.99"))
                .deliveryCost(SeedCatalogue.money("2.82"))
                .productName("Tecno Spark 10").productSku("BP-TEC-SPK10")
                .variantSku("BP-TEC-SPK10-128-BLK").selectedOptions("128 GB, Meta Black")
                .productImageUrl("https://cdn.sujula.gm/sample/products/spark10-front.jpg")
                .assignedImeis("350123456789011").imeiAssignedAt(cat.daysAgo(11))
                .build()));

        payment(cat, order, "PAY-SJL-1001", PaymentStatus.PAID, PaymentMethod.CARD,
                "116.81", "0.00", "EUR", "ch_sample_1001_7f2a", placed.plusMinutes(2), null, null);

        history(cat, order, null, OrderStatus.PENDING, "Placed from Madrid.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.CONFIRMED, "Card charged.", null);
        history(cat, order, OrderStatus.CONFIRMED, OrderStatus.PROCESSING,
                "Seller accepted.", cat.user("fatou"));
        history(cat, order, OrderStatus.PROCESSING, OrderStatus.SHIPPED,
                "Collected by Ebrima Colley.", cat.user("ebrima"));
        history(cat, order, OrderStatus.SHIPPED, OrderStatus.DELIVERED,
                "Code presented at the door in Serrekunda.", cat.user("ebrima"));
    }

    /**
     * One payment, two sellers, two settlement currencies.
     *
     * <p>This is C3's row. The euro charge is one payment; underneath it a
     * Banjul sub-order settles in dalasi and a Dakar one in CFA, each with its
     * own commission, its own delivery cost and its own rate snapshot. Either
     * could be cancelled without touching the other, and here one has already
     * shipped while the other is still being prepared.
     */
    private void sjl1002(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(4);
        Order order = Order.builder()
                .orderNumber("SJL-1002").trackingCode("TRK-SJL-1002")
                .customer(cat.user("isatou"))
                .status(OrderStatus.SHIPPED)
                .subtotal(SeedCatalogue.money("258.20"))
                .shippingCost(SeedCatalogue.money("5.10"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("263.30"))
                .currency("EUR")
                .shippingFullName("Aji Ceesay").shippingPhone("+2207700100")
                .shippingStreet("Sayerr Jobe Avenue, near Westfield")
                .shippingCity("Serrekunda").shippingState("Kanifing").shippingCountry("GM")
                .shippingLatitude(13.4383).shippingLongitude(-16.6781)
                .shippingAddress(cat.address("isatou-serrekunda"))
                .billingFullName("Isatou Ceesay").billingStreet("Calle de Bravo Murillo 145")
                .billingCity("Madrid").billingPostalCode("28020").billingCountry("ES")
                .paymentStatus(PaymentStatus.PAID).paymentMethod(PaymentMethod.CARD)
                .paidAt(placed.plusMinutes(1))
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .loyaltyPointsEarned(263L)
                .build();
        cat.orders.put("1002", cat.save(order));

        VendorOrder banjul = vendorOrder(cat, "1002-bp", order, cat.vendor("banjul-phones"),
                VendorOrderStatus.SHIPPED, "GMD",
                "8500.00", "0.00", "8500.00",
                "113.99", "0.00", "113.99",
                "8.00", "680.00", "210.00", "7820.00",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, placed));
        banjul.setAcceptedAt(placed.plusHours(2));
        banjul.setReadyAt(cat.daysAgo(3));
        banjul.setCollectedAt(cat.daysAgo(2));
        banjul.setReleaseCodeIssueCount(1);
        banjul.setReleaseCodeIssuedAt(cat.daysAgo(2).minusMinutes(30));

        // CFA: every native figure a whole franc. 95 000 less 10% commission is
        // 85 500, and there is no arithmetic here that could produce a centime.
        VendorOrder dakar = vendorOrder(cat, "1002-dt", order, cat.vendor("dakar-tech"),
                VendorOrderStatus.PREPARING, "XOF",
                "95000", "0", "95000",
                "144.21", "0.00", "144.21",
                "10.00", "9500", "1500", "85500",
                FxSnapshot.published("XOF", "EUR", XOF_EUR, placed));
        dakar.setAcceptedAt(placed.plusHours(5));

        cat.orderItems.put("1002-spark10", cat.save(OrderItem.builder()
                .order(order).vendorOrder(banjul)
                .product(cat.product("spark10")).variant(cat.variant("spark10-128-blue"))
                .vendor(cat.vendor("banjul-phones"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("8500.00"))
                .totalPrice(SeedCatalogue.money("8500.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("113.99"))
                .totalPriceConverted(SeedCatalogue.money("113.99"))
                .deliveryCost(SeedCatalogue.money("2.82"))
                .productName("Tecno Spark 10").productSku("BP-TEC-SPK10")
                .variantSku("BP-TEC-SPK10-128-BLU").selectedOptions("128 GB, Meta Blue")
                .productImageUrl("https://cdn.sujula.gm/sample/products/spark10-front.jpg")
                .assignedImeis("350123456789012").imeiAssignedAt(cat.daysAgo(3))
                .build()));

        cat.orderItems.put("1002-a15", cat.save(OrderItem.builder()
                .order(order).vendorOrder(dakar)
                .product(cat.product("galaxya15")).variant(cat.variant("a15-noir"))
                .vendor(cat.vendor("dakar-tech"))
                .quantity(1)
                .unitPrice(SeedCatalogue.wholeUnits("95000"))
                .totalPrice(SeedCatalogue.wholeUnits("95000")).currency("XOF")
                .unitPriceConverted(SeedCatalogue.money("144.21"))
                .totalPriceConverted(SeedCatalogue.money("144.21"))
                .deliveryCost(SeedCatalogue.money("2.28"))
                .productName("Samsung Galaxy A15").productSku("DT-SAM-A15-128")
                .variantSku("DT-SAM-A15-128-NOIR").selectedOptions("Noir")
                .productImageUrl("https://cdn.sujula.gm/sample/products/a15-front.jpg")
                .build()));

        payment(cat, order, "PAY-SJL-1002", PaymentStatus.PAID, PaymentMethod.CARD,
                "263.30", "0.00", "EUR", "ch_sample_1002_3b91", placed.plusMinutes(1), null, null);

        history(cat, order, null, OrderStatus.PENDING, "Placed from Madrid, two sellers.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.CONFIRMED, "Card charged.", null);
        history(cat, order, OrderStatus.CONFIRMED, OrderStatus.PROCESSING,
                "Both sellers accepted.", null);
        history(cat, order, OrderStatus.PROCESSING, OrderStatus.SHIPPED,
                "Banjul Phones dispatched. Dakar Tech still preparing.", cat.user("ebrima"));
    }

    /** London pays, Brikama collects from a pickup point, with a platform coupon. */
    private void sjl1003(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(5);
        Order order = Order.builder()
                .orderNumber("SJL-1003").trackingCode("TRK-SJL-1003")
                .customer(cat.user("modou"))
                .status(OrderStatus.SHIPPED)
                .subtotal(SeedCatalogue.money("111.05"))
                .shippingCost(SeedCatalogue.money("1.71"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("11.11"))
                .total(SeedCatalogue.money("101.65"))
                .currency("GBP")
                .coupon(cat.coupon("welcome10")).couponCode("WELCOME10")
                .shippingFullName("Mariama Jallow").shippingPhone("+2207700200")
                .shippingStreet("Brikama Nyambai Road, opposite the mosque")
                .shippingCity("Brikama").shippingState("West Coast").shippingCountry("GM")
                .shippingLatitude(13.2712).shippingLongitude(-16.6494)
                .shippingAddress(cat.address("modou-brikama"))
                .billingFullName("Modou Jallow").billingStreet("214 Seven Sisters Road")
                .billingCity("London").billingPostalCode("N4 3NX").billingCountry("GB")
                .paymentStatus(PaymentStatus.PAID).paymentMethod(PaymentMethod.PAYPAL)
                .paidAt(placed.plusMinutes(4))
                .deliveryMode(DeliveryMode.PICKUP_POINT)
                .pickupPointId(cat.pickupPoint("brikama").getId())
                .deliveryInstructions("She will collect. Her phone is the one on the order.")
                .loyaltyPointsEarned(101L)
                .build();
        cat.orders.put("1003", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1003-bp", order, cat.vendor("banjul-phones"),
                VendorOrderStatus.SHIPPED, "GMD",
                "9750.00", "975.00", "8775.00",
                "111.05", "11.11", "99.94",
                "8.00", "702.00", "150.00", "8073.00",
                FxSnapshot.published("GMD", "GBP", GMD_GBP, placed));
        vendorOrder.setAcceptedAt(placed.plusHours(1));
        vendorOrder.setReadyAt(cat.daysAgo(4));
        vendorOrder.setCollectedAt(cat.daysAgo(4).plusHours(3));
        vendorOrder.setCoupon(cat.coupon("welcome10"));
        vendorOrder.setCouponCode("WELCOME10");
        vendorOrder.setReleaseCodeIssueCount(1);
        vendorOrder.setReleaseCodeIssuedAt(cat.daysAgo(4).plusHours(2));

        cat.orderItems.put("1003-hot30", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("hot30"))
                .vendor(cat.vendor("banjul-phones"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("9750.00"))
                .totalPrice(SeedCatalogue.money("9750.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("111.05"))
                .totalPriceConverted(SeedCatalogue.money("111.05"))
                .deliveryCost(SeedCatalogue.money("1.71"))
                .productName("Infinix Hot 30").productSku("BP-INF-HOT30")
                .productImageUrl("https://cdn.sujula.gm/sample/products/hot30-front.jpg")
                .assignedImeis("350987654321098").imeiAssignedAt(cat.daysAgo(4))
                .build()));

        payment(cat, order, "PAY-SJL-1003", PaymentStatus.PAID, PaymentMethod.PAYPAL,
                "101.65", "0.00", "GBP", "PAYID-SAMPLE-1003-KX", placed.plusMinutes(4), null, null);

        history(cat, order, null, OrderStatus.PENDING, "Placed from London.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.CONFIRMED, "PayPal completed.", null);
        history(cat, order, OrderStatus.CONFIRMED, OrderStatus.PROCESSING,
                "Seller accepted.", cat.user("fatou"));
        history(cat, order, OrderStatus.PROCESSING, OrderStatus.SHIPPED,
                "On its way to Brikama Market Stationers.", cat.user("saikou"));
    }

    /** Paid by bank transfer, confirmed by hand, out with a driver. */
    private void sjl1004(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(2);
        Order order = Order.builder()
                .orderNumber("SJL-1004").trackingCode("TRK-SJL-1004")
                .customer(cat.user("binta"))
                .status(OrderStatus.SHIPPED)
                .subtotal(SeedCatalogue.money("1450.00"))
                .shippingCost(SeedCatalogue.money("150.00"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("1600.00"))
                .currency("GMD")
                .shippingFullName("Binta Touray").shippingPhone("+2207200111")
                .shippingStreet("12 Rene Blain Street")
                .shippingCity("Banjul").shippingState("Banjul").shippingCountry("GM")
                .shippingLatitude(13.4549).shippingLongitude(-16.5790)
                .shippingAddress(cat.address("binta-banjul"))
                .billingFullName("Binta Touray").billingStreet("12 Rene Blain Street")
                .billingCity("Banjul").billingCountry("GM")
                .paymentStatus(PaymentStatus.PAID).paymentMethod(PaymentMethod.BANK_TRANSFER)
                .paidAt(placed.plusHours(20))
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .loyaltyPointsEarned(16L)
                .build();
        cat.orders.put("1004", cat.save(order));

        // Buyer and seller in the same currency. The snapshot records an
        // identity rather than being left empty.
        VendorOrder vendorOrder = vendorOrder(cat, "1004-sh", order, cat.vendor("serrekunda-home"),
                VendorOrderStatus.SHIPPED, "GMD",
                "1450.00", "0.00", "1450.00",
                "1450.00", "0.00", "1450.00",
                "12.00", "174.00", "150.00", "1276.00",
                FxSnapshot.identity("GMD", placed));
        vendorOrder.setAcceptedAt(placed.plusHours(21));
        vendorOrder.setReadyAt(cat.daysAgo(1));
        vendorOrder.setCollectedAt(cat.hoursAgo(26));
        vendorOrder.setReleaseCodeIssueCount(1);
        vendorOrder.setReleaseCodeIssuedAt(cat.hoursAgo(27));

        cat.orderItems.put("1004-pot", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("castironpot"))
                .vendor(cat.vendor("serrekunda-home"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("1450.00"))
                .totalPrice(SeedCatalogue.money("1450.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("1450.00"))
                .totalPriceConverted(SeedCatalogue.money("1450.00"))
                .deliveryCost(SeedCatalogue.money("150.00"))
                .productName("Cast iron cooking pot, 8 litre").productSku("SH-KIR-POT8L")
                .productImageUrl("https://cdn.sujula.gm/sample/products/pot-8l.jpg")
                .build()));

        Payment payment = payment(cat, order, "PAY-SJL-1004", PaymentStatus.PAID,
                PaymentMethod.BANK_TRANSFER, "1600.00", "0.00", "GMD",
                null, placed.plusHours(20), null, null);
        payment.setInstructions("Trust Bank Gambia, account 0011002233445, reference SJL-1004.");
        payment.setCollectionReference("TBG-TRF-88410");
        payment.setConfirmedBy(cat.user("support"));
        payment.setNote("Transfer confirmed against the bank statement.");

        history(cat, order, null, OrderStatus.PENDING, "Placed, awaiting transfer.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.CONFIRMED,
                "Transfer seen on the statement.", cat.user("support"));
        history(cat, order, OrderStatus.CONFIRMED, OrderStatus.PROCESSING,
                "Seller accepted.", cat.user("awa"));
        history(cat, order, OrderStatus.PROCESSING, OrderStatus.SHIPPED,
                "Collected by Ebrima Colley.", cat.user("ebrima"));
    }

    /** Cancelled before anything was charged. */
    private void sjl1005(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(2);
        Order order = Order.builder()
                .orderNumber("SJL-1005").trackingCode("TRK-SJL-1005")
                .customer(cat.user("binta"))
                .status(OrderStatus.CANCELLED)
                .subtotal(SeedCatalogue.money("3200.00"))
                .shippingCost(SeedCatalogue.money("220.00"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("3420.00"))
                .currency("GMD")
                .shippingFullName("Binta Touray").shippingPhone("+2207200111")
                .shippingStreet("12 Rene Blain Street")
                .shippingCity("Banjul").shippingState("Banjul").shippingCountry("GM")
                .shippingAddress(cat.address("binta-banjul"))
                .paymentStatus(PaymentStatus.CANCELLED).paymentMethod(PaymentMethod.CARD)
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .internalNotes("Seller had none left; the listing was pulled the same day.")
                .build();
        cat.orders.put("1005", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1005-sh", order, cat.vendor("serrekunda-home"),
                VendorOrderStatus.CANCELLED, "GMD",
                "3200.00", "0.00", "3200.00",
                "3200.00", "0.00", "3200.00",
                "12.00", "0.00", "220.00", "0.00",
                FxSnapshot.identity("GMD", placed));
        vendorOrder.setCancelledAt(placed.plusHours(6));
        vendorOrder.setRejectionReason("Out of stock. The listing has been unpublished.");

        cat.orderItems.put("1005-blender", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("blender"))
                .vendor(cat.vendor("serrekunda-home"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("3200.00"))
                .totalPrice(SeedCatalogue.money("3200.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("3200.00"))
                .totalPriceConverted(SeedCatalogue.money("3200.00"))
                .deliveryCost(SeedCatalogue.money("220.00"))
                .productName("Blender 1500 W with grinder").productSku("SH-BLD-1500")
                .build()));

        Payment payment = payment(cat, order, "PAY-SJL-1005", PaymentStatus.CANCELLED,
                PaymentMethod.CARD, "3420.00", "0.00", "GMD", null, null, null,
                placed.plusHours(6));
        payment.setNote("Authorisation released without capture.");

        history(cat, order, null, OrderStatus.PENDING, "Placed.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.CANCELLED,
                "Seller could not fulfil.", cat.user("awa"));
    }

    /** Dakar to Dakar, delivered, then one line refunded. */
    private void sjl1006(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(18);
        Order order = Order.builder()
                .orderNumber("SJL-1006").trackingCode("TRK-SJL-1006")
                .customer(cat.user("cheikh"))
                .status(OrderStatus.DELIVERED)
                .subtotal(SeedCatalogue.wholeUnits("107500"))
                .shippingCost(SeedCatalogue.wholeUnits("1500"))
                .taxAmount(SeedCatalogue.wholeUnits("0"))
                .discount(SeedCatalogue.wholeUnits("0"))
                .total(SeedCatalogue.wholeUnits("109000"))
                .currency("XOF")
                .shippingFullName("Cheikh Ndiaye").shippingPhone("+221770333444")
                .shippingStreet("Avenue Cheikh Anta Diop, Point E")
                .shippingCity("Dakar").shippingState("Dakar").shippingCountry("SN")
                .shippingLatitude(14.6937).shippingLongitude(-17.4441)
                .shippingAddress(cat.address("cheikh-dakar"))
                .paymentStatus(PaymentStatus.PARTIALLY_REFUNDED).paymentMethod(PaymentMethod.CARD)
                .paidAt(placed.plusMinutes(3))
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .build();
        cat.orders.put("1006", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1006-dt", order, cat.vendor("dakar-tech"),
                VendorOrderStatus.DELIVERED, "XOF",
                "107500", "0", "107500",
                "107500", "0", "107500",
                "10.00", "10750", "1500", "96750",
                FxSnapshot.identity("XOF", placed));
        vendorOrder.setAcceptedAt(placed.plusHours(2));
        vendorOrder.setReadyAt(cat.daysAgo(17));
        vendorOrder.setCollectedAt(cat.daysAgo(17).plusHours(4));
        vendorOrder.setReceiptConfirmedAt(cat.daysAgo(16));
        vendorOrder.setEscrowReleasedAt(cat.daysAgo(16).plusHours(2));
        vendorOrder.setReleaseCodeIssueCount(1);
        vendorOrder.setReleaseCodeIssuedAt(cat.daysAgo(17).plusHours(3));

        cat.orderItems.put("1006-a15", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("galaxya15")).variant(cat.variant("a15-bleu"))
                .vendor(cat.vendor("dakar-tech"))
                .quantity(1)
                .unitPrice(SeedCatalogue.wholeUnits("95000"))
                .totalPrice(SeedCatalogue.wholeUnits("95000")).currency("XOF")
                .unitPriceConverted(SeedCatalogue.wholeUnits("95000"))
                .totalPriceConverted(SeedCatalogue.wholeUnits("95000"))
                .deliveryCost(SeedCatalogue.wholeUnits("1500"))
                .productName("Samsung Galaxy A15").productSku("DT-SAM-A15-128")
                .variantSku("DT-SAM-A15-128-BLEU").selectedOptions("Bleu")
                .build()));

        cat.orderItems.put("1006-powerbank", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("powerbank"))
                .vendor(cat.vendor("dakar-tech"))
                .quantity(1)
                .unitPrice(SeedCatalogue.wholeUnits("12500"))
                .totalPrice(SeedCatalogue.wholeUnits("12500")).currency("XOF")
                .unitPriceConverted(SeedCatalogue.wholeUnits("12500"))
                .totalPriceConverted(SeedCatalogue.wholeUnits("12500"))
                .deliveryCost(SeedCatalogue.wholeUnits("0"))
                .productName("Anker PowerCore 20000").productSku("DT-ANK-PC20K")
                .build()));

        payment(cat, order, "PAY-SJL-1006", PaymentStatus.PARTIALLY_REFUNDED, PaymentMethod.CARD,
                "109000", "12500", "XOF", "ch_sample_1006_9de2",
                placed.plusMinutes(3), cat.daysAgo(10), null);

        history(cat, order, null, OrderStatus.PENDING, "Commande passée.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.CONFIRMED, "Carte débitée.", null);
        history(cat, order, OrderStatus.CONFIRMED, OrderStatus.PROCESSING,
                "Vendeur a accepté.", cat.user("omar"));
        history(cat, order, OrderStatus.PROCESSING, OrderStatus.SHIPPED,
                "Ramassé par Aminata Sarr.", cat.user("aminata"));
        history(cat, order, OrderStatus.SHIPPED, OrderStatus.DELIVERED,
                "Code présenté à la porte.", cat.user("aminata"));
    }

    /** Refunded in full after a dispute went the buyer's way. */
    private void sjl1007(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(25);
        Order order = Order.builder()
                .orderNumber("SJL-1007").trackingCode("TRK-SJL-1007")
                .customer(cat.user("sally"))
                .status(OrderStatus.REFUNDED)
                .subtotal(SeedCatalogue.money("24.81"))
                .shippingCost(SeedCatalogue.money("2.01"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("26.82"))
                .currency("EUR")
                .shippingFullName("Fatoumata Mendy").shippingPhone("+2207700300")
                .shippingStreet("Kololi, near the craft market")
                .shippingCity("Kololi").shippingState("West Coast").shippingCountry("GM")
                .shippingLatitude(13.4470).shippingLongitude(-16.6950)
                .billingFullName("Sally Mendy").billingStreet("18 Rue du Faubourg Saint-Denis")
                .billingCity("Paris").billingPostalCode("75010").billingCountry("FR")
                .paymentStatus(PaymentStatus.REFUNDED).paymentMethod(PaymentMethod.CARD)
                .paidAt(placed.plusMinutes(2))
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .internalNotes("Dispute DSP-000001 decided for the buyer.")
                .build();
        cat.orders.put("1007", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1007-ks", order, cat.vendor("kololi-style"),
                VendorOrderStatus.REFUNDED, "GMD",
                "1850.00", "0.00", "1850.00",
                "24.81", "0.00", "24.81",
                "10.00", "185.00", "150.00", "1665.00",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, placed));
        vendorOrder.setAcceptedAt(placed.plusHours(4));
        vendorOrder.setReadyAt(cat.daysAgo(24));
        vendorOrder.setCollectedAt(cat.daysAgo(23));
        vendorOrder.setDisputeFrozenAt(cat.daysAgo(20));

        cat.orderItems.put("1007-wax", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("waxfabric")).variant(cat.variant("wax-6"))
                .vendor(cat.vendor("kololi-style"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("1850.00"))
                .totalPrice(SeedCatalogue.money("1850.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("24.81"))
                .totalPriceConverted(SeedCatalogue.money("24.81"))
                .deliveryCost(SeedCatalogue.money("2.01"))
                .productName("Wax print fabric, 6 yards").productSku("KS-FAB-WAX6")
                .variantSku("KS-FAB-WAX-6").selectedOptions("6 yards")
                .build()));

        payment(cat, order, "PAY-SJL-1007", PaymentStatus.REFUNDED, PaymentMethod.CARD,
                "26.82", "26.82", "EUR", "ch_sample_1007_4a77",
                placed.plusMinutes(2), cat.daysAgo(14), null);

        history(cat, order, null, OrderStatus.PENDING, "Commande passée depuis Paris.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.CONFIRMED, "Carte débitée.", null);
        history(cat, order, OrderStatus.CONFIRMED, OrderStatus.PROCESSING, "Accepté.", cat.user("lamin"));
        history(cat, order, OrderStatus.PROCESSING, OrderStatus.SHIPPED, "Ramassé.", cat.user("saikou"));
        history(cat, order, OrderStatus.SHIPPED, OrderStatus.REFUNDED,
                "Litige tranché en faveur de l'acheteuse; remboursement intégral.",
                cat.user("support"));
    }

    /** A guest. No account anywhere in this row, and cash on delivery. */
    private void sjl1008(SeedCatalogue cat) {
        LocalDateTime placed = cat.hoursAgo(6);
        Order order = Order.builder()
                .orderNumber("SJL-1008").trackingCode("TRK-SJL-1008")
                .guestName("Ousainou Bojang").guestEmail("ousainou.bojang@example.gm")
                .guestPhone("+2207700400")
                .guestSessionId("9f3c21ab-77de-4a10-9b2e-sample000001")
                .status(OrderStatus.PROCESSING)
                .subtotal(SeedCatalogue.money("8500.00"))
                .shippingCost(SeedCatalogue.money("150.00"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("8650.00"))
                .currency("GMD")
                .shippingFullName("Ousainou Bojang").shippingPhone("+2207700400")
                .shippingStreet("Latrikunda German, behind the school")
                .shippingCity("Serrekunda").shippingState("Kanifing").shippingCountry("GM")
                .shippingLatitude(13.4330).shippingLongitude(-16.6690)
                .paymentStatus(PaymentStatus.PENDING).paymentMethod(PaymentMethod.PAY_ON_DELIVERY)
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .contactlessDelivery(false)
                .deliveryInstructions("Call before coming; the gate is usually locked.")
                .build();
        cat.orders.put("1008", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1008-bp", order, cat.vendor("banjul-phones"),
                VendorOrderStatus.READY_FOR_PICKUP, "GMD",
                "8500.00", "0.00", "8500.00",
                "8500.00", "0.00", "8500.00",
                "8.00", "680.00", "150.00", "7820.00",
                FxSnapshot.identity("GMD", placed));
        vendorOrder.setAcceptedAt(placed.plusMinutes(40));
        vendorOrder.setReadyAt(cat.hoursAgo(1));
        vendorOrder.setReleaseCodeIssueCount(1);
        vendorOrder.setReleaseCodeIssuedAt(cat.hoursAgo(1));

        cat.orderItems.put("1008-spark10", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("spark10")).variant(cat.variant("spark10-128-black"))
                .vendor(cat.vendor("banjul-phones"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("8500.00"))
                .totalPrice(SeedCatalogue.money("8500.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("8500.00"))
                .totalPriceConverted(SeedCatalogue.money("8500.00"))
                .deliveryCost(SeedCatalogue.money("150.00"))
                .productName("Tecno Spark 10").productSku("BP-TEC-SPK10")
                .variantSku("BP-TEC-SPK10-128-BLK").selectedOptions("128 GB, Meta Black")
                .build()));

        Payment payment = payment(cat, order, "PAY-SJL-1008", PaymentStatus.PENDING,
                PaymentMethod.PAY_ON_DELIVERY, "8650.00", "0.00", "GMD",
                null, null, null, null);
        payment.setInstructions("The driver collects D 8 650 in cash at the door.");

        history(cat, order, null, OrderStatus.PENDING, "Placed as a guest.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.PROCESSING,
                "Seller accepted; cash collected at the door.", cat.user("fatou"));
    }

    /** Authorised and not yet captured. */
    private void sjl1009(SeedCatalogue cat) {
        LocalDateTime placed = cat.hoursAgo(20);
        Order order = Order.builder()
                .orderNumber("SJL-1009").trackingCode("TRK-SJL-1009")
                .customer(cat.user("modou"))
                .status(OrderStatus.CONFIRMED)
                .subtotal(SeedCatalogue.money("244.89"))
                .shippingCost(SeedCatalogue.money("1.71"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("246.60"))
                .currency("GBP")
                .shippingFullName("Mariama Jallow").shippingPhone("+2207700200")
                .shippingStreet("Brikama Nyambai Road, opposite the mosque")
                .shippingCity("Brikama").shippingState("West Coast").shippingCountry("GM")
                .shippingLatitude(13.2712).shippingLongitude(-16.6494)
                .shippingAddress(cat.address("modou-brikama"))
                .billingFullName("Modou Jallow").billingStreet("214 Seven Sisters Road")
                .billingCity("London").billingPostalCode("N4 3NX").billingCountry("GB")
                .paymentStatus(PaymentStatus.AUTHORIZED).paymentMethod(PaymentMethod.CARD)
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .scheduledDate(java.time.LocalDate.now().plusDays(2))
                .scheduledTimeSlot("14:00-17:00")
                .contactlessDelivery(true)
                .build();
        cat.orders.put("1009", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1009-bp", order, cat.vendor("banjul-phones"),
                VendorOrderStatus.PENDING, "GMD",
                "21500.00", "0.00", "21500.00",
                "244.89", "0.00", "244.89",
                "8.00", "1720.00", "150.00", "19780.00",
                FxSnapshot.published("GMD", "GBP", GMD_GBP, placed));

        cat.orderItems.put("1009-iphone11", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("iphone11"))
                .vendor(cat.vendor("banjul-phones"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("21500.00"))
                .totalPrice(SeedCatalogue.money("21500.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("244.89"))
                .totalPriceConverted(SeedCatalogue.money("244.89"))
                .deliveryCost(SeedCatalogue.money("1.71"))
                .productName("iPhone 11 64GB (refurbished)").productSku("BP-APL-IP11-64")
                .productImageUrl("https://cdn.sujula.gm/sample/products/iphone11-front.jpg")
                .build()));

        payment(cat, order, "PAY-SJL-1009", PaymentStatus.AUTHORIZED, PaymentMethod.CARD,
                "246.60", "0.00", "GBP", "ch_sample_1009_b103", null, null, null);

        history(cat, order, null, OrderStatus.PENDING, "Placed from London.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.CONFIRMED,
                "Card authorised; capture on dispatch.", null);
    }

    /** The card was declined, and the sub-order was cancelled behind it. */
    private void sjl1010(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(1);
        Order order = Order.builder()
                .orderNumber("SJL-1010").trackingCode("TRK-SJL-1010")
                .customer(cat.user("yankuba"))
                .status(OrderStatus.PENDING)
                .subtotal(SeedCatalogue.money("1000.00"))
                .shippingCost(SeedCatalogue.money("120.00"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("1120.00"))
                .currency("GMD")
                .shippingFullName("Yankuba Bah").shippingPhone("+2207200222")
                .shippingStreet("Atlantic Road, Bakau New Town")
                .shippingCity("Bakau").shippingState("Kanifing").shippingCountry("GM")
                .shippingAddress(cat.address("yankuba-bakau"))
                .paymentStatus(PaymentStatus.FAILED).paymentMethod(PaymentMethod.CASH_IN_STORE)
                .deliveryMode(DeliveryMode.VENDOR_PICKUP)
                .internalNotes("Buyer never came to the shop to pay.")
                .build();
        cat.orders.put("1010", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1010-bp", order, cat.vendor("banjul-phones"),
                VendorOrderStatus.PENDING, "GMD",
                "1000.00", "0.00", "1000.00",
                "1000.00", "0.00", "1000.00",
                "8.00", "80.00", "120.00", "920.00",
                FxSnapshot.identity("GMD", placed));

        cat.orderItems.put("1010-parts", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("partslot"))
                .vendor(cat.vendor("banjul-phones"))
                .quantity(2)
                .unitPrice(SeedCatalogue.money("500.00"))
                .totalPrice(SeedCatalogue.money("1000.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("500.00"))
                .totalPriceConverted(SeedCatalogue.money("1000.00"))
                .deliveryCost(SeedCatalogue.money("120.00"))
                .productName("Assorted handset boards — sold for parts")
                .productSku("BP-PRT-LOT01")
                .build()));

        Payment payment = payment(cat, order, "PAY-SJL-1010", PaymentStatus.FAILED,
                PaymentMethod.CASH_IN_STORE, "1120.00", "0.00", "GMD",
                null, null, null, null);
        payment.setFailureReason("Nobody came to the shop within the 24-hour window.");
        payment.setInstructions("Pay at Banjul Phones, 41 Liberation Avenue, within 24 hours.");

        history(cat, order, null, OrderStatus.PENDING, "Placed, to be paid in the shop.", null);
    }

    /** On its way to a pickup point, to be paid on collection. */
    private void sjl1011(SeedCatalogue cat) {
        LocalDateTime placed = cat.daysAgo(3);
        Order order = Order.builder()
                .orderNumber("SJL-1011").trackingCode("TRK-SJL-1011")
                .customer(cat.user("mariama"))
                .status(OrderStatus.SHIPPED)
                .subtotal(SeedCatalogue.money("225.33"))
                .shippingCost(SeedCatalogue.money("23.31"))
                .taxAmount(SeedCatalogue.money("0.00"))
                .discount(SeedCatalogue.money("0.00"))
                .total(SeedCatalogue.money("248.64"))
                .currency("SEK")
                .shippingFullName("Kaddy Secka").shippingPhone("+2207700500")
                .shippingStreet("Collect from Westfield Corner Pharmacy")
                .shippingCity("Serrekunda").shippingState("Kanifing").shippingCountry("GM")
                .shippingLatitude(13.4405).shippingLongitude(-16.6775)
                .billingFullName("Mariama Sowe").billingStreet("Rinkebysvängen 72")
                .billingCity("Stockholm").billingPostalCode("163 74").billingCountry("SE")
                .paymentStatus(PaymentStatus.PENDING).paymentMethod(PaymentMethod.PAY_AT_PICKUP)
                .deliveryMode(DeliveryMode.PICKUP_POINT)
                .pickupPointId(cat.pickupPoint("westfield").getId())
                .deliveryInstructions("She has no smartphone. The code goes to her by SMS.")
                .build();
        cat.orders.put("1011", cat.save(order));

        VendorOrder vendorOrder = vendorOrder(cat, "1011-sh", order, cat.vendor("serrekunda-home"),
                VendorOrderStatus.SHIPPED, "GMD",
                "1450.00", "0.00", "1450.00",
                "225.33", "0.00", "225.33",
                "12.00", "174.00", "150.00", "1276.00",
                FxSnapshot.published("GMD", "SEK", GMD_SEK, placed));
        vendorOrder.setAcceptedAt(placed.plusHours(3));
        vendorOrder.setReadyAt(cat.daysAgo(1));
        vendorOrder.setCollectedAt(cat.hoursAgo(4));
        vendorOrder.setReleaseCodeIssueCount(1);
        vendorOrder.setReleaseCodeIssuedAt(cat.hoursAgo(5));

        cat.orderItems.put("1011-pot", cat.save(OrderItem.builder()
                .order(order).vendorOrder(vendorOrder)
                .product(cat.product("castironpot"))
                .vendor(cat.vendor("serrekunda-home"))
                .quantity(1)
                .unitPrice(SeedCatalogue.money("1450.00"))
                .totalPrice(SeedCatalogue.money("1450.00")).currency("GMD")
                .unitPriceConverted(SeedCatalogue.money("225.33"))
                .totalPriceConverted(SeedCatalogue.money("225.33"))
                .deliveryCost(SeedCatalogue.money("23.31"))
                .productName("Cast iron cooking pot, 8 litre").productSku("SH-KIR-POT8L")
                .build()));

        Payment payment = payment(cat, order, "PAY-SJL-1011", PaymentStatus.PENDING,
                PaymentMethod.PAY_AT_PICKUP, "248.64", "0.00", "SEK",
                null, null, null, null);
        payment.setInstructions("Pay at Westfield Corner Pharmacy when collecting.");

        history(cat, order, null, OrderStatus.PENDING, "Placed from Stockholm.", null);
        history(cat, order, OrderStatus.PENDING, OrderStatus.PROCESSING,
                "Seller accepted; to be left at Westfield.", cat.user("awa"));
        history(cat, order, OrderStatus.PROCESSING, OrderStatus.SHIPPED,
                "Collected by Jainaba Drammeh for the Westfield counter.", cat.user("jainaba"));
    }

    // ── Builders shared by the orders above ──────────────────────────────────

    private VendorOrder vendorOrder(SeedCatalogue cat, String key, Order order, Vendor vendor,
                                    VendorOrderStatus status, String nativeCurrency,
                                    String subtotalNative, String discountNative,
                                    String totalNative, String subtotal, String discount,
                                    String total, String commissionRate, String commissionNative,
                                    String deliveryNative, String payoutNative, FxSnapshot fx) {
        VendorOrder vendorOrder = VendorOrder.builder()
                .order(order).vendor(vendor).status(status)
                .nativeCurrency(nativeCurrency)
                .subtotalNative(new BigDecimal(subtotalNative))
                .discountNative(new BigDecimal(discountNative))
                .totalNative(new BigDecimal(totalNative))
                .subtotal(new BigDecimal(subtotal))
                .discount(new BigDecimal(discount))
                .total(new BigDecimal(total))
                .commissionRate(new BigDecimal(commissionRate))
                .commissionNative(new BigDecimal(commissionNative))
                .deliveryNative(new BigDecimal(deliveryNative))
                .payoutNative(new BigDecimal(payoutNative))
                .fx(fx)
                .build();
        cat.vendorOrders.put(key, cat.save(vendorOrder));
        return vendorOrder;
    }

    private Payment payment(SeedCatalogue cat, Order order, String reference, PaymentStatus status,
                            PaymentMethod method, String amount, String refunded, String currency,
                            String transactionId, LocalDateTime paidAt, LocalDateTime refundedAt,
                            LocalDateTime cancelledAt) {
        Payment payment = Payment.builder()
                .order(order).reference(reference).status(status).method(method)
                .amount(new BigDecimal(amount)).amountRefunded(new BigDecimal(refunded))
                .currency(currency).transactionId(transactionId)
                .paidAt(paidAt).refundedAt(refundedAt).cancelledAt(cancelledAt)
                .build();
        cat.save(payment);
        // Both sides, so the in-memory graph matches what a read would return.
        order.setPayment(payment);
        return payment;
    }

    private void history(SeedCatalogue cat, Order order, OrderStatus from, OrderStatus to,
                         String note, com.sujula.model.user.User by) {
        cat.save(OrderStatusHistory.builder()
                .order(order).fromStatus(from).toStatus(to).notes(note).changedBy(by)
                .build());
    }

    // ── Coupon usage ─────────────────────────────────────────────────────────

    private void couponUsages(SeedCatalogue cat) {
        cat.save(CouponUsage.builder().coupon(cat.coupon("welcome10"))
                .user(cat.user("modou")).orderId(cat.order("1003").getId()).build());
        cat.save(CouponUsage.builder().coupon(cat.coupon("diaspora500"))
                .user(cat.user("isatou")).orderId(cat.order("1001").getId()).build());
        cat.save(CouponUsage.builder().coupon(cat.coupon("freeship"))
                .user(cat.user("binta")).orderId(cat.order("1004").getId()).build());
        cat.save(CouponUsage.builder().coupon(cat.coupon("dt5000"))
                .user(cat.user("cheikh")).orderId(cat.order("1006").getId()).build());
        cat.save(CouponUsage.builder().coupon(cat.coupon("bp15"))
                .user(cat.user("modou")).orderId(cat.order("1009").getId()).build());
        cat.save(CouponUsage.builder().coupon(cat.coupon("expired"))
                .user(cat.user("sally")).orderId(cat.order("1007").getId()).build());
        // A guest used it. No user on the row, and the count still has to move.
        cat.save(CouponUsage.builder().coupon(cat.coupon("freeship"))
                .orderId(cat.order("1008").getId()).build());
    }

    // ── Refunds ──────────────────────────────────────────────────────────────

    /**
     * Refunds, one per status the request can hold.
     *
     * <p>Each is against a <em>vendor order</em> rather than the order — C3's
     * rule. A partial refund here is the whole of one seller's line, not a
     * proportion of a basket that two sellers shared.
     */
    private void refundRequests(SeedCatalogue cat) {
        cat.save(RefundRequest.builder()
                .reference("RFD-2024-000001")
                .order(cat.order("1006")).vendorOrder(cat.vendorOrder("1006-dt"))
                .requestedBy(cat.user("cheikh"))
                .status(RefundRequestStatus.COMPLETED)
                .amount(SeedCatalogue.wholeUnits("12500")).currency("XOF")
                .amountNative(SeedCatalogue.wholeUnits("12500"))
                .fx(FxSnapshot.identity("XOF", cat.daysAgo(12)))
                .reason("Le power bank ne tient pas la charge.")
                .decidedBy(cat.user("support")).decidedAt(cat.daysAgo(11))
                .decisionNote("Défaut confirmé par le vendeur.")
                .paymentId(null).completedAt(cat.daysAgo(10))
                .createdAt(cat.daysAgo(12))
                .build());

        cat.save(RefundRequest.builder()
                .reference("RFD-2024-000002")
                .order(cat.order("1007")).vendorOrder(cat.vendorOrder("1007-ks"))
                .requestedBy(cat.user("sally"))
                .status(RefundRequestStatus.COMPLETED)
                .amount(SeedCatalogue.money("26.82")).currency("EUR")
                .amountNative(SeedCatalogue.money("2000.00"))
                .fx(FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(25)))
                .reason("Tissu différent de la description.")
                .decidedBy(cat.user("support")).decidedAt(cat.daysAgo(15))
                .decisionNote("Litige tranché pour l'acheteuse; remboursement intégral.")
                .completedAt(cat.daysAgo(14))
                .createdAt(cat.daysAgo(18))
                .build());

        cat.save(RefundRequest.builder()
                .reference("RFD-2024-000003")
                .order(cat.order("1002")).vendorOrder(cat.vendorOrder("1002-dt"))
                .requestedBy(cat.user("isatou"))
                .status(RefundRequestStatus.REQUESTED)
                .amount(SeedCatalogue.money("144.21")).currency("EUR")
                .amountNative(SeedCatalogue.wholeUnits("95000"))
                .fx(FxSnapshot.published("XOF", "EUR", XOF_EUR, cat.daysAgo(4)))
                .reason("Taking too long to dispatch; may cancel this half of the order.")
                .createdAt(cat.hoursAgo(10))
                .build());

        cat.save(RefundRequest.builder()
                .reference("RFD-2024-000004")
                .order(cat.order("1001")).vendorOrder(cat.vendorOrder("1001-bp"))
                .requestedBy(cat.user("isatou"))
                .status(RefundRequestStatus.DECLINED)
                .amount(SeedCatalogue.money("113.99")).currency("EUR")
                .amountNative(SeedCatalogue.money("8500.00"))
                .fx(FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(12)))
                .reason("Wrong colour sent.")
                .decidedBy(cat.user("support")).decidedAt(cat.daysAgo(7))
                .decisionNote("The photographs show the colour that was ordered.")
                .createdAt(cat.daysAgo(8))
                .build());

        cat.save(RefundRequest.builder()
                .reference("RFD-2024-000005")
                .order(cat.order("1003")).vendorOrder(cat.vendorOrder("1003-bp"))
                .requestedBy(cat.user("modou"))
                .status(RefundRequestStatus.APPROVED)
                .amount(SeedCatalogue.money("11.39")).currency("GBP")
                .amountNative(SeedCatalogue.money("1000.00"))
                .fx(FxSnapshot.published("GMD", "GBP", GMD_GBP, cat.daysAgo(5)))
                .reason("Charger missing from the box. Partial refund agreed.")
                .decidedBy(cat.user("support")).decidedAt(cat.hoursAgo(20))
                .decisionNote("Approved. Waiting on the provider to move the money.")
                .createdAt(cat.daysAgo(1))
                .build());

        cat.save(RefundRequest.builder()
                .reference("RFD-2024-000006")
                .order(cat.order("1004")).vendorOrder(cat.vendorOrder("1004-sh"))
                .requestedBy(cat.user("binta"))
                .status(RefundRequestStatus.WITHDRAWN)
                .amount(SeedCatalogue.money("1600.00")).currency("GMD")
                .amountNative(SeedCatalogue.money("1600.00"))
                .fx(FxSnapshot.identity("GMD", cat.daysAgo(2)))
                .reason("Changed my mind.")
                .decisionNote("Withdrawn by the buyer before anyone decided.")
                .createdAt(cat.hoursAgo(30))
                .build());
    }
}
