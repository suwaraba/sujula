package com.sujula.service.impl;

import com.sujula.dto.request.order.CartItemRequest;
import com.sujula.dto.request.order.CreateOrderRequest;
import com.sujula.dto.request.order.GuestCheckoutRequest;
import com.sujula.dto.request.order.OrderScheduleRequest;
import com.sujula.dto.request.order.UpdateOrderStatusRequest;
import com.sujula.dto.request.order.UserCheckoutRequest;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.dto.response.order.OrderAdminDto;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Address;
import com.sujula.model.constant.CouponScope;
import com.sujula.model.constant.CouponType;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.OrderStatusHistory;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Coupon;
import com.sujula.model.products.CouponUsage;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductImage;
import com.sujula.model.products.ProductOptionValue;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.OrderStatusHistoryRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.product.CouponRepository;
import com.sujula.repository.product.CouponUsageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.CartService;
import com.sujula.service.DeliveryPricingService;
import com.sujula.service.EmailService;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.NotificationService;
import com.sujula.service.OrderService;
import com.sujula.service.cart.CartOwner;
import com.sujula.service.cart.RateTable;
import com.sujula.service.delivery.DeliveryDestination;
import com.sujula.service.delivery.DeliveryQuote;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Order placement and lifecycle for a multivendor, multicurrency storefront.
 *
 * <p>Two placement styles are supported:
 *
 * <ul>
 *   <li><b>From the cart</b> ({@link #createFromCart} and the cart branch of
 *       {@link #createGuestOrder}) — the cart has already priced, discounted
 *       and validated everything, so checkout trusts that quote verbatim
 *       rather than re-deriving prices, and only takes the pessimistic locks
 *       needed to deduct stock safely.</li>
 *   <li><b>From an explicit item list</b> ({@link #create}, {@link #createUserOrder},
 *       and the non-cart branch of {@link #createGuestOrder}) — there is no
 *       cart to trust, so prices, vendor grouping and any single coupon are
 *       resolved here using the same native-currency-per-vendor +
 *       batch-rate-lookup approach the cart uses.</li>
 * </ul>
 *
 * <p>Either way the result is split into one {@link VendorOrder} per vendor —
 * fulfilment, cancellation and payout all happen per vendor — each carrying
 * its own native settlement-currency amounts alongside the buyer's
 * display-currency amounts.
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private static final Set<OrderStatus> TERMINAL_STATUSES = Set.of(
            OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.REFUNDED);

    private static final Set<OrderStatus> CUSTOMER_CANCELLABLE = Set.of(
            OrderStatus.PENDING, OrderStatus.CONFIRMED);

    private static final Set<VendorOrderStatus> VENDOR_TERMINAL = Set.of(
            VendorOrderStatus.DELIVERED, VendorOrderStatus.CANCELLED, VendorOrderStatus.REFUNDED);

    private static final Set<PartnerStatus> SELLABLE = EnumSet.of(PartnerStatus.APPROVED, PartnerStatus.ACTIVE);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final OrderRepository orderRepository;
    private final VendorOrderRepository vendorOrderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final UserRepository userRepository;
    private final VendorRepository vendorRepository;
    private final AddressRepository addressRepository;
    private final PickupPointRepository pickupPointRepository;
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final ExchangeRateService exchangeRateService;
    private final CartService cartService;
    private final DeliveryPricingService deliveryPricingService;
    private final EmailService emailService;
    private final NotificationService notificationService;

    /** Fallback when a vendor or coupon has no currency recorded. */
    @Value("${sujula.cart.default-currency:GMD}")
    private String defaultCurrency;

    // ─────────────────────────────────────────────────────────────────────────
    // Placement — explicit item list (admin/API + direct frontend checkout)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order create(Long customerId, CreateOrderRequest request) {
        User customer = requireUser(customerId);
        Address address = requireOwnedAddress(request.getShippingAddressId(), customerId);

        List<ItemSpec> specs = toSpecs(request.getItems());
        CheckoutResult result = priceExplicitItems(specs, defaultCurrency, request.getCouponCode(), customerId);
        applyDeliveryPricing(result, DeliveryDestination.of(address), DeliveryMode.HOME_DELIVERY);

        Order order = Order.builder()
                .orderNumber(newOrderNumber())
                .customer(customer)
                .status(OrderStatus.PENDING)
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(result.subtotal)
                .shippingCost(result.shipping)
                .taxAmount(BigDecimal.ZERO)
                .discount(result.discount)
                .total(result.total)
                .currency(result.currency)
                .coupon(result.platformCoupon)
                .couponCode(orderLevelCouponCode(result))
                .shippingFullName(address.getFullName())
                .shippingPhone(address.getPhone())
                .shippingStreet(address.getStreet())
                .shippingApartment(address.getApartmentSuite())
                .shippingCity(address.getCity())
                .shippingState(address.getState())
                .shippingPostalCode(address.getPostalCode())
                .shippingCountry(address.getCountryCode())
                .notes(request.getNotes())
                .build();

        Order finalOrder = persistOrder(order, result);
        recordCouponUsage(result, customer, finalOrder.getId());
        dispatchOrderCreationEvents(customer, finalOrder, result.vendorOrders);
        return finalOrder;
    }

    @Override
    @Transactional
    public Order createUserOrder(Long userId, UserCheckoutRequest request) {
        User customer = requireUser(userId);

        List<ItemSpec> specs = request.getItems().stream()
                .map(i -> new ItemSpec(i.getProductId(), i.getVariantId(), i.getQuantity()))
                .toList();

        String requestedCurrency = (request.getCurrency() != null && !request.getCurrency().isBlank())
                ? request.getCurrency() : defaultCurrency;
        CheckoutResult result = priceExplicitItems(specs, requestedCurrency, request.getCouponCode(), userId);

        UserCheckoutRequest.RecipientInfo recipient = request.getRecipient();
        String shippingStreet = request.getDeliveryAddress() != null
                ? request.getDeliveryAddress().getAddress()
                : (request.getPickupPointId() != null ? "Pickup Point #" + request.getPickupPointId() : "");

        // Collecting from a pickup point is a shorter, cheaper leg than delivery
        // to the door, and it is the hub — not the buyer's address — the parcel
        // actually travels to, so it is the hub that prices the delivery.
        DeliveryMode mode = request.getPickupPointId() != null
                ? DeliveryMode.PICKUP_POINT : DeliveryMode.HOME_DELIVERY;
        DeliveryDestination destination = (mode == DeliveryMode.PICKUP_POINT)
                ? DeliveryDestination.of(requirePickupPoint(request.getPickupPointId()))
                : new DeliveryDestination(null, null, shippingStreet,
                        recipient.getCity(), recipient.getRegion(), null, recipient.getCountry());
        applyDeliveryPricing(result, destination, mode);

        Order order = Order.builder()
                .orderNumber(newOrderNumber())
                .customer(customer)
                .status(OrderStatus.PENDING)
                .deliveryMode(mode)
                .pickupPointId(request.getPickupPointId())
                .subtotal(result.subtotal)
                .shippingCost(result.shipping)
                .taxAmount(BigDecimal.ZERO)
                .discount(result.discount)
                .total(result.total)
                .currency(result.currency)
                .coupon(result.platformCoupon)
                .couponCode(orderLevelCouponCode(result))
                .shippingFullName(recipient.getName())
                .shippingPhone(recipient.getPhone())
                .shippingStreet(shippingStreet)
                .shippingCity(recipient.getCity())
                .shippingState(recipient.getRegion())
                .shippingCountry(recipient.getCountry())
                .notes(request.getNotes())
                .build();

        Order finalOrder = persistOrder(order, result);
        recordCouponUsage(result, customer, finalOrder.getId());
        dispatchOrderCreationEvents(customer, finalOrder, result.vendorOrders);
        return finalOrder;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Placement — from the shopper's cart
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order createFromCart(Long userId, Long shippingAddressId, String notes, String displayCurrency) {
        User customer = requireUser(userId);
        Address address = requireOwnedAddress(shippingAddressId, userId);

        CartOwner owner = CartOwner.user(userId);
        CartResponse quote = cartService.getCart(owner, displayCurrency);
        requireCheckoutable(quote);

        CheckoutResult result = buildFromQuote(quote);
        applyDeliveryPricing(result, DeliveryDestination.of(address), DeliveryMode.HOME_DELIVERY);

        Order order = Order.builder()
                .orderNumber(newOrderNumber())
                .customer(customer)
                .status(OrderStatus.PENDING)
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(result.subtotal)
                .shippingCost(result.shipping)
                .taxAmount(BigDecimal.ZERO)
                .discount(result.discount)
                .total(result.total)
                .currency(result.currency)
                .coupon(result.platformCoupon)
                .couponCode(orderLevelCouponCode(result))
                .shippingFullName(address.getFullName())
                .shippingPhone(address.getPhone())
                .shippingStreet(address.getStreet())
                .shippingApartment(address.getApartmentSuite())
                .shippingCity(address.getCity())
                .shippingState(address.getState())
                .shippingPostalCode(address.getPostalCode())
                .shippingCountry(address.getCountryCode())
                .notes(notes)
                .build();

        Order finalOrder = persistOrder(order, result);
        recordCouponUsage(result, customer, finalOrder.getId());
        cartService.clearCart(owner);
        dispatchOrderCreationEvents(customer, finalOrder, result.vendorOrders);
        return finalOrder;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guest checkout
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order createGuestOrder(GuestCheckoutRequest request) {
        CartOwner guestOwner = null;
        CheckoutResult result;

        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            guestOwner = CartOwner.guest(request.getSessionId());
            CartResponse quote = cartService.getCart(guestOwner, request.getCurrency());
            requireCheckoutable(quote);
            result = buildFromQuote(quote);
        } else {
            if (request.getItems() == null || request.getItems().isEmpty()) {
                throw new BadRequestException("Provide either a sessionId (guest cart) or an explicit items list");
            }
            List<ItemSpec> specs = toSpecs(request.getItems());
            String requestedCurrency = (request.getCurrency() != null && !request.getCurrency().isBlank())
                    ? request.getCurrency() : defaultCurrency;
            result = priceExplicitItems(specs, requestedCurrency, request.getCouponCode(), null);
        }

        applyDeliveryPricing(result,
                new DeliveryDestination(null, null,
                        request.getShippingStreet(), request.getShippingCity(), request.getShippingState(),
                        request.getShippingPostalCode(), request.getShippingCountry()),
                DeliveryMode.HOME_DELIVERY);

        Order order = Order.builder()
                .orderNumber(newOrderNumber())
                .guestName(request.getGuestName())
                .guestEmail(request.getGuestEmail().toLowerCase().trim())
                .guestPhone(request.getGuestPhone())
                .guestSessionId(request.getSessionId())
                .status(OrderStatus.PENDING)
                .deliveryMode(DeliveryMode.HOME_DELIVERY)
                .subtotal(result.subtotal)
                .shippingCost(result.shipping)
                .taxAmount(BigDecimal.ZERO)
                .discount(result.discount)
                .total(result.total)
                .currency(result.currency)
                .coupon(result.platformCoupon)
                .couponCode(orderLevelCouponCode(result))
                .shippingFullName(request.getShippingFullName())
                .shippingPhone(request.getShippingPhone())
                .shippingStreet(request.getShippingStreet())
                .shippingApartment(request.getShippingApartment())
                .shippingCity(request.getShippingCity())
                .shippingState(request.getShippingState())
                .shippingPostalCode(request.getShippingPostalCode())
                .shippingCountry(request.getShippingCountry())
                .notes(request.getNotes())
                .build();

        Order finalOrder = persistOrder(order, result);
        recordCouponUsage(result, null, finalOrder.getId());

        if (guestOwner != null) {
            cartService.clearCart(guestOwner);
        }

        dispatchGuestOrderCreationEvents(finalOrder, result.vendorOrders);
        return finalOrder;
    }

    @Override
    @Transactional(readOnly = true)
    public Order findGuestOrder(String orderNumber, String guestEmail) {
        return orderRepository.findByOrderNumberAndGuestEmailIgnoreCase(orderNumber, guestEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "no matching guest order"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public OrderAdminDto findById(Long id) {
        Order order = orderRepository.findByIdFetchAll(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
        return OrderAdminDto.toAdminDto(order);
    }

    @Override
    public Order findByOrderNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "no order with number " + orderNumber));
    }

    @Override
    public Page<Order> findByCustomer(Long customerId, Pageable pageable) {
        return orderRepository.findByCustomerIdFetchCustomer(customerId, pageable);
    }

    @Override
    public Page<Order> findByVendor(Long vendorId, Pageable pageable) {
        return orderRepository.findByItems_VendorId(vendorId, pageable);
    }

    @Override
    public Page<Order> findByStatus(OrderStatus status, Pageable pageable) {
        return orderRepository.findByStatusFetchCustomer(status, pageable);
    }

    @Override
    public Page<Order> findAll(Pageable pageable) {
        return orderRepository.findAllFetchCustomer(pageable);
    }

    @Override
    public List<OrderStatusHistory> getStatusHistory(Long orderId) {
        orderRepository.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return statusHistoryRepository.findByOrderIdOrderByChangedAtAsc(orderId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status transitions
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order updateStatus(Long orderId, UpdateOrderStatusRequest request) {
        return updateStatus(orderId, null, request);
    }

    @Override
    @Transactional
    public Order updateStatus(Long orderId, Long adminUserId, UpdateOrderStatusRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        OrderStatus from = order.getStatus();
        OrderStatus to = request.getStatus();

        if (TERMINAL_STATUSES.contains(from) && !(from == OrderStatus.CANCELLED && to == OrderStatus.REFUNDED)) {
            throw new BadRequestException("Cannot change order status from " + from + " to " + to);
        }

        if (to == OrderStatus.CANCELLED && from != OrderStatus.CANCELLED) {
            restoreStock(order);
            cancelVendorOrders(order);
        }

        order.setStatus(to);
        if (request.getNotes() != null) {
            order.setNotes(request.getNotes());
        }
        Order saved = orderRepository.save(order);

        User changedBy = adminUserId != null ? userRepository.findById(adminUserId).orElse(null) : null;

        statusHistoryRepository.save(OrderStatusHistory.builder()
                .order(saved)
                .fromStatus(from)
                .toStatus(to)
                .notes(request.getNotes())
                .changedBy(changedBy)
                .build());

        if (saved.getCustomer() != null) {
            try {
                notificationService.send(saved.getCustomer().getId(), orderStatusTitle(to),
                        "Order " + saved.getOrderNumber() + " " + orderStatusBody(to),
                        "ORDER", saved.getOrderNumber());
            } catch (Exception ignored) {
                // Never let a notification failure roll back the status change
            }
        }

        return saved;
    }

    @Override
    @Transactional
    public Order cancelByCustomer(Long orderId, Long customerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (order.isGuestOrder()) {
            throw new BadRequestException("Guest orders cannot be cancelled through this endpoint");
        }
        if (!order.getCustomer().getId().equals(customerId)) {
            throw new BadRequestException("Order does not belong to this user");
        }
        if (!CUSTOMER_CANCELLABLE.contains(order.getStatus())) {
            throw new BadRequestException("Order cannot be cancelled at this stage. Current status: "
                    + order.getStatus() + ". Only PENDING or CONFIRMED orders may be cancelled by the customer.");
        }

        return updateStatus(orderId, UpdateOrderStatusRequest.builder()
                .status(OrderStatus.CANCELLED).notes("Cancelled by customer").build());
    }

    @Override
    @Transactional
    public Order cancelGuestOrder(String orderNumber, String guestEmail) {
        Order order = findGuestOrder(orderNumber, guestEmail);

        if (!CUSTOMER_CANCELLABLE.contains(order.getStatus())) {
            throw new BadRequestException("Order cannot be cancelled at this stage. Current status: "
                    + order.getStatus() + ". Only PENDING or CONFIRMED orders may be cancelled.");
        }

        return updateStatus(order.getId(), UpdateOrderStatusRequest.builder()
                .status(OrderStatus.CANCELLED).notes("Cancelled by guest").build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scheduling
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order updateSchedule(String orderNumber, Long userId, OrderScheduleRequest request) {
        Order order = findByOrderNumber(orderNumber);
        if (order.isGuestOrder() || !order.getCustomer().getId().equals(userId)) {
            throw new BadRequestException("Order does not belong to this user");
        }
        if (!CUSTOMER_CANCELLABLE.contains(order.getStatus())) {
            throw new BadRequestException("Delivery schedule can only be changed while the order is pending or confirmed");
        }

        order.setScheduledDate(request.getScheduledDate());
        order.setScheduledTimeSlot(request.getScheduledTimeSlot());
        order.setDeliveryInstructions(request.getDeliveryInstructions());
        order.setContactlessDelivery(request.isContactlessDelivery());
        return orderRepository.save(order);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reorder
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartResponse reorder(String orderNumber, Long userId) {
        Order order = findByOrderNumber(orderNumber);
        if (order.isGuestOrder() || !order.getCustomer().getId().equals(userId)) {
            throw new BadRequestException("Order does not belong to this user");
        }

        CartOwner owner = CartOwner.user(userId);
        for (OrderItem item : order.getItems()) {
            try {
                cartService.addItem(owner, CartItemRequest.builder()
                        .productId(item.getProduct().getId())
                        .variantId(item.getVariant() != null ? item.getVariant().getId() : null)
                        .quantity(item.getQuantity())
                        .build(), null);
            } catch (RuntimeException ex) {
                log.info("Reorder skipped '{}' from order {}: {}", item.getProductName(), orderNumber, ex.getMessage());
            }
        }
        return cartService.getCart(owner, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pricing — from an already-priced cart quote
    // ─────────────────────────────────────────────────────────────────────────

    private void requireCheckoutable(CartResponse quote) {
        if (quote.getVendors() == null || quote.getVendors().isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }
        if (!quote.isTotalsComplete()) {
            throw new BadRequestException("Cart totals are incomplete — a currency conversion rate is missing");
        }
        List<String> blocked = quote.getVendors().stream()
                .filter(g -> !g.isCheckoutable())
                .map(CartResponse.VendorGroup::getStoreName)
                .toList();
        if (!blocked.isEmpty()) {
            throw new BadRequestException("Some items can't be checked out right now: " + String.join(", ", blocked));
        }
    }

    private CheckoutResult buildFromQuote(CartResponse quote) {
        Coupon platformCoupon = findCouponByCode(quote.getPlatformCouponCode());
        List<VendorOrder> vendorOrders = new ArrayList<>();

        for (CartResponse.VendorGroup group : quote.getVendors()) {
            Vendor vendor = vendorRepository.findById(group.getVendorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor", group.getVendorId()));

            List<OrderItem> items = new ArrayList<>();
            for (CartResponse.CartItemResponse itemResp : group.getItems()) {
                items.add(lockAndBuildItem(itemResp));
            }

            Coupon vendorCoupon = findCouponByCode(group.getVendorCouponCode());

            BigDecimal subtotalNative = group.getSubtotalNative();
            BigDecimal exchangeRate = group.getExchangeRate();
            BigDecimal discountNative = null;
            BigDecimal totalNative = null;
            if (subtotalNative != null && exchangeRate != null && exchangeRate.signum() > 0) {
                discountNative = group.getDiscount().divide(exchangeRate, RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
                totalNative = subtotalNative.subtract(discountNative);
            }

            vendorOrders.add(VendorOrder.builder()
                    .vendor(vendor)
                    .status(VendorOrderStatus.PENDING)
                    .nativeCurrency(group.getNativeCurrency())
                    .subtotalNative(subtotalNative)
                    .discountNative(discountNative)
                    .totalNative(totalNative)
                    .subtotal(group.getSubtotal())
                    .discount(group.getDiscount())
                    .total(group.getTotal())
                    .coupon(vendorCoupon)
                    .couponCode(group.getVendorCouponCode())
                    .items(items)
                    .build());
        }

        CheckoutResult result = new CheckoutResult();
        result.subtotal = quote.getSubtotal();
        result.discount = quote.getDiscount();
        result.total = quote.getTotal();
        result.currency = quote.getDisplayCurrency();
        result.platformCoupon = platformCoupon;
        result.vendorOrders = vendorOrders;
        return result;
    }

    private OrderItem lockAndBuildItem(CartResponse.CartItemResponse itemResp) {
        Product product = productRepository.findByIdForUpdate(itemResp.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", itemResp.getProductId()));
        if (!product.isActive()) {
            throw new BadRequestException("Product is no longer available: " + product.getName());
        }

        ProductVariant variant = null;
        if (itemResp.getVariantId() != null) {
            variant = variantRepository.findByIdForUpdate(itemResp.getVariantId())
                    .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", itemResp.getVariantId()));
            if (!variant.isActive()) {
                throw new BadRequestException("Selected option for " + product.getName() + " is no longer available");
            }
        }

        int quantity = itemResp.getQuantity();
        int available = availableStock(product, variant);
        if (quantity > available) {
            throw new BadRequestException("Only " + available + " left of " + product.getName());
        }
        deductStock(product, variant, quantity);

        return OrderItem.builder()
                .product(product)
                .variant(variant)
                .vendor(product.getVendor())
                .quantity(quantity)
                .unitPrice(itemResp.getUnitPriceNative())
                .totalPrice(itemResp.getLineTotalNative())
                .currency(itemResp.getNativeCurrency())
                .unitPriceConverted(itemResp.getUnitPrice())
                .totalPriceConverted(itemResp.getLineTotal())
                .productName(itemResp.getProductName())
                .productSku(product.getSku())
                .variantSku(itemResp.getVariantSku())
                .selectedOptions(itemResp.getVariantLabel())
                .productImageUrl(itemResp.getImageUrl())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pricing — explicit item list (no cart involved)
    // ─────────────────────────────────────────────────────────────────────────

    private record ItemSpec(Long productId, Long variantId, int quantity) {}

    private static List<ItemSpec> toSpecs(List<CreateOrderRequest.OrderItemRequest> items) {
        return items.stream()
                .map(i -> new ItemSpec(i.getProductId(), i.getVariantId(), i.getQuantity()))
                .toList();
    }

    private CheckoutResult priceExplicitItems(List<ItemSpec> specs, String displayCurrency,
                                              String couponCode, Long userId) {
        if (specs.isEmpty()) {
            throw new BadRequestException("No items to order");
        }
        String target = requireCurrencyCode(displayCurrency);

        record Resolved(Product product, ProductVariant variant, Vendor vendor,
                         String nativeCurrency, BigDecimal unitPriceNative, int quantity) {}

        List<Resolved> resolved = new ArrayList<>();
        for (ItemSpec spec : specs) {
            Product product = productRepository.findByIdForUpdate(spec.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", spec.productId()));
            requireSellable(product);

            ProductVariant variant = null;
            if (spec.variantId() != null) {
                variant = variantRepository.findByIdForUpdate(spec.variantId())
                        .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", spec.variantId()));
                if (!variant.getProduct().getId().equals(product.getId())) {
                    throw new BadRequestException("Variant does not belong to product: " + product.getName());
                }
                if (!variant.isActive()) {
                    throw new BadRequestException("Selected variant is no longer available for: " + product.getName());
                }
            }

            int available = availableStock(product, variant);
            if (spec.quantity() > available) {
                throw new BadRequestException(
                        "Insufficient stock for: " + product.getName() + " (available: " + available + ")");
            }
            deductStock(product, variant, spec.quantity());

            BigDecimal unitPriceNative = (variant != null ? variant.getEffectivePrice() : product.getPrice())
                    .setScale(RateTable.MONEY_SCALE, RoundingMode.HALF_UP);

            resolved.add(new Resolved(product, variant, product.getVendor(),
                    settlementCurrency(product.getVendor()), unitPriceNative, spec.quantity()));
        }

        Coupon coupon = findCouponByCode(couponCode);

        Set<String> currencies = resolved.stream()
                .map(Resolved::nativeCurrency)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (coupon != null) {
            currencies.add(couponCurrency(coupon));
        }
        currencies.remove(target);
        Map<String, BigDecimal> rates = currencies.isEmpty()
                ? Map.of() : exchangeRateService.getLatestRates(target, currencies);
        RateTable rateTable = new RateTable(target, rates);

        Map<Long, List<Resolved>> byVendor = resolved.stream()
                .collect(Collectors.groupingBy(r -> r.vendor().getId(), LinkedHashMap::new, Collectors.toList()));

        Long couponVendorId = null;
        if (coupon != null && coupon.getScope() == CouponScope.VENDOR) {
            if (coupon.getVendor() == null) {
                throw new BadRequestException("This coupon is misconfigured and cannot be applied");
            }
            if (!byVendor.containsKey(coupon.getVendor().getId())) {
                throw new BadRequestException("This coupon only applies to items from " + coupon.getVendor().getStoreName());
            }
            couponVendorId = coupon.getVendor().getId();
        }

        List<VendorOrder> vendorOrders = new ArrayList<>();
        BigDecimal grandSubtotal = BigDecimal.ZERO;

        for (List<Resolved> items : byVendor.values()) {
            Vendor vendor = items.get(0).vendor();
            String nativeCurrency = items.get(0).nativeCurrency();

            BigDecimal subtotalNative = BigDecimal.ZERO;
            BigDecimal subtotalConverted = BigDecimal.ZERO;
            List<OrderItem> orderItems = new ArrayList<>();

            for (Resolved r : items) {
                BigDecimal lineNative = r.unitPriceNative().multiply(BigDecimal.valueOf(r.quantity()));
                BigDecimal unitConverted = rateTable.convert(r.unitPriceNative(), nativeCurrency);
                BigDecimal lineConverted = rateTable.convert(lineNative, nativeCurrency);
                if (lineConverted == null) {
                    throw new BadRequestException("No exchange rate available for " + nativeCurrency + " to " + target);
                }
                subtotalNative = subtotalNative.add(lineNative);
                subtotalConverted = subtotalConverted.add(lineConverted);

                orderItems.add(OrderItem.builder()
                        .product(r.product())
                        .variant(r.variant())
                        .vendor(vendor)
                        .quantity(r.quantity())
                        .unitPrice(r.unitPriceNative())
                        .totalPrice(lineNative)
                        .currency(nativeCurrency)
                        .unitPriceConverted(unitConverted)
                        .totalPriceConverted(lineConverted)
                        .productName(r.product().getName())
                        .productSku(r.product().getSku())
                        .variantSku(r.variant() != null ? r.variant().getSku() : null)
                        .selectedOptions(variantLabel(r.variant()))
                        .productImageUrl(primaryImageUrl(r.product()))
                        .build());
            }

            vendorOrders.add(VendorOrder.builder()
                    .vendor(vendor)
                    .status(VendorOrderStatus.PENDING)
                    .nativeCurrency(nativeCurrency)
                    .subtotalNative(subtotalNative)
                    .discountNative(BigDecimal.ZERO)
                    .totalNative(subtotalNative)
                    .subtotal(subtotalConverted)
                    .discount(BigDecimal.ZERO)
                    .total(subtotalConverted)
                    .items(orderItems)
                    .build());
            grandSubtotal = grandSubtotal.add(subtotalConverted);
        }

        BigDecimal grandDiscount = BigDecimal.ZERO;
        Coupon platformCoupon = null;

        if (coupon != null) {
            if (coupon.getScope() == CouponScope.VENDOR) {
                Long finalCouponVendorId = couponVendorId;
                VendorOrder target1 = vendorOrders.stream()
                        .filter(vo -> vo.getVendor().getId().equals(finalCouponVendorId))
                        .findFirst().orElseThrow();
                validateCoupon(coupon, userId, target1.getSubtotal(), rateTable);

                BigDecimal discount = couponDiscount(coupon, target1.getSubtotal(), rateTable);
                target1.setDiscount(discount);
                target1.setTotal(target1.getSubtotal().subtract(discount));
                target1.setCoupon(coupon);
                target1.setCouponCode(coupon.getCode());
                if (target1.getSubtotalNative() != null) {
                    BigDecimal exchangeRate = rateTable.rateFor(target1.getNativeCurrency());
                    if (exchangeRate != null && exchangeRate.signum() > 0) {
                        BigDecimal discountNative = discount.divide(exchangeRate, RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
                        target1.setDiscountNative(discountNative);
                        target1.setTotalNative(target1.getSubtotalNative().subtract(discountNative));
                    }
                }
                grandDiscount = discount;
            } else {
                validateCoupon(coupon, userId, grandSubtotal, rateTable);
                BigDecimal discount = couponDiscount(coupon, grandSubtotal, rateTable);
                grandDiscount = discount;
                platformCoupon = coupon;

                if (discount.signum() > 0 && grandSubtotal.signum() > 0) {
                    BigDecimal allocated = BigDecimal.ZERO;
                    for (int i = 0; i < vendorOrders.size(); i++) {
                        VendorOrder vo = vendorOrders.get(i);
                        BigDecimal share;
                        if (i == vendorOrders.size() - 1) {
                            share = discount.subtract(allocated);
                        } else {
                            share = discount.multiply(vo.getSubtotal())
                                    .divide(grandSubtotal, RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
                            allocated = allocated.add(share);
                        }
                        vo.setDiscount(share);
                        vo.setTotal(vo.getSubtotal().subtract(share));
                    }
                }
            }
        }

        CheckoutResult result = new CheckoutResult();
        result.subtotal = grandSubtotal;
        result.discount = grandDiscount;
        result.total = grandSubtotal.subtract(grandDiscount);
        result.currency = target;
        result.platformCoupon = platformCoupon;
        result.vendorOrders = vendorOrders;
        return result;
    }

    private int availableStock(Product product, ProductVariant variant) {
        if (product.isAllowBackorder()) {
            return Integer.MAX_VALUE;
        }
        Integer stock = variant != null ? variant.getStock() : product.getStock();
        return stock != null ? Math.max(stock, 0) : 0;
    }

    private void deductStock(Product product, ProductVariant variant, int quantity) {
        if (variant != null) {
            variant.setStock(variant.getStock() - quantity);
            variantRepository.save(variant);
        } else {
            product.setStock(product.getStock() - quantity);
            productRepository.save(product);
        }
    }

    private void requireSellable(Product product) {
        if (!product.isActive()) {
            throw new BadRequestException("Product is not available: " + product.getName());
        }
        Vendor vendor = product.getVendor();
        if (vendor == null) {
            throw new BadRequestException("Product has no vendor and cannot be purchased");
        }
        if (!SELLABLE.contains(vendor.getStatus())) {
            throw new BadRequestException(vendor.getStoreName() + " is not currently accepting orders");
        }
        String listing = product.getPriceCurrency();
        String settlement = settlementCurrency(vendor);
        if (listing != null && !listing.isBlank() && !listing.equalsIgnoreCase(settlement)) {
            log.error("Product {} is priced in {} but vendor {} settles in {}",
                    product.getId(), listing, vendor.getId(), settlement);
            throw new BadRequestException("This listing is misconfigured and cannot be purchased right now");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delivery pricing — one leg per product, never one figure per order
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prices every product's delivery separately and folds the result into the
     * checkout.
     *
     * <p>A multivendor basket has no single origin: two lines can leave from two
     * towns, weigh different amounts and ship under different scopes, so each
     * line is quoted on its own distance and weight and keeps its own share on
     * {@code OrderItem.deliveryCost}. The order's {@code shippingCost} is only
     * the sum of those legs, which is what makes per-vendor payout and per-item
     * cancellation able to account for delivery later on.
     *
     * <p>A {@code FREE_SHIPPING} coupon is honoured here rather than as a
     * discount: it waives the legs it covers — every leg for a platform coupon,
     * that vendor's legs for a vendor-scoped one — so the buyer sees delivery
     * priced at zero instead of a discount line that happens to cancel it out.
     */
    private void applyDeliveryPricing(CheckoutResult result, DeliveryDestination destination, DeliveryMode mode) {
        List<OrderItem> items = new ArrayList<>();
        Set<Long> freeShippingVendors = new HashSet<>();
        for (VendorOrder vo : result.vendorOrders) {
            if (vo.getCoupon() != null && vo.getCoupon().getType() == CouponType.FREE_SHIPPING) {
                freeShippingVendors.add(vo.getVendor().getId());
            }
            items.addAll(vo.getItems());
        }
        if (items.isEmpty()) {
            result.shipping = BigDecimal.ZERO;
            return;
        }

        boolean freeShippingEverywhere = result.platformCoupon != null
                && result.platformCoupon.getType() == CouponType.FREE_SHIPPING;

        DeliveryQuote quote = deliveryPricingService.quoteOrderItems(items, destination, mode, result.currency);
        if (!quote.complete()) {
            throw new BadRequestException(
                    "Delivery cannot be priced in " + result.currency + " right now — please try again shortly");
        }

        BigDecimal shipping = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            DeliveryQuote.DeliveryLeg leg = quote.legs().get(i);
            Long vendorId = item.getVendor() != null ? item.getVendor().getId() : null;

            BigDecimal cost = (freeShippingEverywhere || freeShippingVendors.contains(vendorId))
                    ? BigDecimal.ZERO
                    : leg.cost();
            item.setDeliveryCost(cost);
            shipping = shipping.add(cost);
        }

        result.shipping = shipping;
        result.total = result.total.add(shipping);
    }

    private PickupPoint requirePickupPoint(Long pickupPointId) {
        PickupPoint point = pickupPointRepository.findById(pickupPointId)
                .orElseThrow(() -> new ResourceNotFoundException("PickupPoint", pickupPointId));
        PartnerStatus status = point.getStatus();
        if (!point.isActive() || (status != PartnerStatus.APPROVED && status != PartnerStatus.ACTIVE)) {
            throw new BadRequestException(point.getName() + " is not accepting parcels right now");
        }
        return point;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Accumulator for a priced-but-not-yet-persisted checkout, from either pricing path. */
    private static final class CheckoutResult {
        BigDecimal subtotal;
        BigDecimal discount;
        /** Sum of the per-product delivery legs, in {@link #currency}. */
        BigDecimal shipping = BigDecimal.ZERO;
        BigDecimal total;
        String currency;
        Coupon platformCoupon;
        List<VendorOrder> vendorOrders;
    }

    private Order persistOrder(Order order, CheckoutResult result) {
        Order savedOrder = orderRepository.save(order);

        List<OrderItem> allItems = new ArrayList<>();
        for (VendorOrder vo : result.vendorOrders) {
            vo.setOrder(savedOrder);
            VendorOrder savedVo = vendorOrderRepository.save(vo);
            for (OrderItem item : savedVo.getItems()) {
                item.setOrder(savedOrder);
                item.setVendorOrder(savedVo);
                allItems.add(item);
            }
        }
        savedOrder.setItems(allItems);
        Order finalOrder = orderRepository.save(savedOrder);

        statusHistoryRepository.save(OrderStatusHistory.builder()
                .order(finalOrder)
                .toStatus(OrderStatus.PENDING)
                .notes("Order placed")
                .build());

        return finalOrder;
    }

    private void recordCouponUsage(CheckoutResult result, User customer, Long orderId) {
        if (result.platformCoupon != null) {
            saveCouponUsage(result.platformCoupon, customer, orderId);
        }
        for (VendorOrder vo : result.vendorOrders) {
            if (vo.getCoupon() != null) {
                saveCouponUsage(vo.getCoupon(), customer, orderId);
            }
        }
    }

    private void saveCouponUsage(Coupon coupon, User user, Long orderId) {
        couponUsageRepository.save(CouponUsage.builder()
                .coupon(coupon)
                .user(user)
                .orderId(orderId)
                .build());
        coupon.setUsageCount((coupon.getUsageCount() == null ? 0 : coupon.getUsageCount()) + 1);
        couponRepository.save(coupon);
    }

    private void restoreStock(Order order) {
        for (OrderItem item : order.getItems()) {
            if (item.getVariant() != null) {
                ProductVariant v = item.getVariant();
                v.setStock(v.getStock() + item.getQuantity());
                variantRepository.save(v);
            } else {
                Product p = item.getProduct();
                p.setStock(p.getStock() + item.getQuantity());
                productRepository.save(p);
            }
        }
    }

    private void cancelVendorOrders(Order order) {
        for (VendorOrder vo : vendorOrderRepository.findByOrderId(order.getId())) {
            if (!VENDOR_TERMINAL.contains(vo.getStatus())) {
                vo.setStatus(VendorOrderStatus.CANCELLED);
                vo.setCancelledAt(LocalDateTime.now());
                vendorOrderRepository.save(vo);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Coupon math (mirrors CartServiceImpl's — orders have no cart to delegate to)
    // ─────────────────────────────────────────────────────────────────────────

    private Coupon findCouponByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return couponRepository.findByCode(code.trim().toUpperCase()).orElse(null);
    }

    private void validateCoupon(Coupon coupon, Long userId, BigDecimal baseInDisplay, RateTable rates) {
        String reason = couponInvalidReason(coupon, userId);
        if (reason != null) {
            throw new BadRequestException("Coupon cannot be used: " + reason);
        }
        if (coupon.getMinimumOrderAmount() != null) {
            BigDecimal minimum = rates.convert(coupon.getMinimumOrderAmount(), couponCurrency(coupon));
            if (minimum == null) {
                throw new BadRequestException("Cannot check this coupon's minimum in " + rates.target() + " right now");
            }
            if (baseInDisplay.compareTo(minimum) < 0) {
                throw new BadRequestException("Minimum spend for this coupon is " + minimum + " " + rates.target());
            }
        }
    }

    private String couponInvalidReason(Coupon coupon, Long userId) {
        if (coupon == null) {
            return "it no longer exists";
        }
        if (!coupon.isActive()) {
            return "it is no longer active";
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStartsAt() != null && now.isBefore(coupon.getStartsAt())) {
            return "it is not valid yet";
        }
        if (coupon.getExpiresAt() != null && now.isAfter(coupon.getExpiresAt())) {
            return "it has expired";
        }
        if (coupon.getUsageLimit() != null && coupon.getUsageCount() != null
                && coupon.getUsageCount() >= coupon.getUsageLimit()) {
            return "it has reached its usage limit";
        }
        if (userId != null && coupon.getPerUserLimit() != null
                && couponUsageRepository.countByCouponIdAndUserId(coupon.getId(), userId) >= coupon.getPerUserLimit()) {
            return "you have already used it the maximum number of times";
        }
        return null;
    }

    private BigDecimal couponDiscount(Coupon coupon, BigDecimal base, RateTable rates) {
        if (coupon == null || base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount;
        switch (coupon.getType()) {
            case PERCENTAGE -> {
                discount = base.multiply(coupon.getValue()).divide(HUNDRED, RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
                BigDecimal cap = rates.convert(coupon.getMaximumDiscountAmount(), couponCurrency(coupon));
                if (cap != null) {
                    discount = discount.min(cap);
                }
            }
            case FIXED_AMOUNT -> {
                BigDecimal value = rates.convert(coupon.getValue(), couponCurrency(coupon));
                discount = value != null ? value : BigDecimal.ZERO;
            }
            case FREE_SHIPPING -> discount = BigDecimal.ZERO;
            default -> discount = BigDecimal.ZERO;
        }
        return discount.max(BigDecimal.ZERO).min(base);
    }

    private String couponCurrency(Coupon coupon) {
        String currency = coupon.getCurrency();
        return (currency == null || currency.isBlank()) ? defaultCurrency : currency.toUpperCase();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Currency / misc helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String settlementCurrency(Vendor vendor) {
        String currency = vendor != null ? vendor.getSettlementCurrency() : null;
        return (currency == null || currency.isBlank()) ? defaultCurrency : currency.toUpperCase();
    }

    private String requireCurrencyCode(String code) {
        if (code == null || code.trim().length() != 3 || !code.trim().chars().allMatch(Character::isLetter)) {
            throw new BadRequestException("Currency must be a 3-letter ISO 4217 code");
        }
        return code.trim().toUpperCase();
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private Address requireOwnedAddress(Long addressId, Long userId) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address", addressId));
        if (!address.getUser().getId().equals(userId)) {
            throw new BadRequestException("Address does not belong to this user");
        }
        return address;
    }

    private static String newOrderNumber() {
        return "SJL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String orderLevelCouponCode(CheckoutResult result) {
        if (result.platformCoupon != null) {
            return result.platformCoupon.getCode();
        }
        return result.vendorOrders.stream()
                .map(VendorOrder::getCouponCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String variantLabel(ProductVariant variant) {
        if (variant == null || variant.getSelectedValues() == null || variant.getSelectedValues().isEmpty()) {
            return null;
        }
        return variant.getSelectedValues().stream()
                .sorted(Comparator.comparing(ProductOptionValue::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ProductOptionValue::getDisplayValue)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    private static String primaryImageUrl(Product product) {
        if (product == null || product.getImages() == null) {
            return null;
        }
        return product.getImages().stream()
                .filter(ProductImage::isDefault)
                .findFirst()
                .or(() -> product.getImages().stream().findFirst())
                .map(ProductImage::getImageUrl)
                .orElse(null);
    }

    private static String orderStatusTitle(OrderStatus status) {
        return switch (status) {
            case CONFIRMED  -> "Order Confirmed";
            case PROCESSING -> "Order Being Prepared";
            case SHIPPED    -> "Order Shipped";
            case DELIVERED  -> "Order Delivered";
            case CANCELLED  -> "Order Cancelled";
            case REFUNDED   -> "Refund Processed";
            default         -> "Order Update";
        };
    }

    private static String orderStatusBody(OrderStatus status) {
        return switch (status) {
            case CONFIRMED  -> "has been confirmed and will be prepared shortly.";
            case PROCESSING -> "is currently being prepared.";
            case SHIPPED    -> "is on its way to you.";
            case DELIVERED  -> "has been delivered. Enjoy!";
            case CANCELLED  -> "has been cancelled.";
            case REFUNDED   -> "has been refunded to your original payment method.";
            default         -> "status has been updated to " + status.name().toLowerCase() + ".";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications (fire-and-forget; never roll back the order)
    // ─────────────────────────────────────────────────────────────────────────

    private void dispatchOrderCreationEvents(User customer, Order order, List<VendorOrder> vendorOrders) {
        try {
            notificationService.send(customer.getId(), "Order Placed Successfully",
                    "Your order " + order.getOrderNumber() + " has been placed and is being processed.",
                    "ORDER", order.getOrderNumber());
            notifyVendors(order, vendorOrders, "New Order Received",
                    "You have a new order (" + order.getOrderNumber() + ") to fulfil.");
        } catch (Exception ignored) {
        }
    }

    private void dispatchGuestOrderCreationEvents(Order order, List<VendorOrder> vendorOrders) {
        try {
            notifyVendors(order, vendorOrders, "New Guest Order Received",
                    "A new guest order (" + order.getOrderNumber() + ") requires fulfilment.");
        } catch (Exception ignored) {
        }
    }

    private void notifyVendors(Order order, List<VendorOrder> vendorOrders, String title, String message) {
        for (VendorOrder vo : vendorOrders) {
            Vendor vendor = vo.getVendor();
            if (vendor.getUser() != null) {
                emailService.sendVendorOrderNotification(vendor.getUser().getEmail(), vendor.getStoreName(), order.getOrderNumber());
                notificationService.send(vendor.getUser().getId(), title, message, "ORDER", order.getOrderNumber());
            }
        }
    }
}
