package com.sujula.service.impl;

import com.sujula.dto.request.CreateOrderRequest;
import com.sujula.dto.request.GuestCheckoutRequest;
import com.sujula.dto.request.OrderScheduleRequest;
import com.sujula.dto.request.UpdateOrderStatusRequest;
import com.sujula.dto.request.UserCheckoutRequest;
import com.sujula.dto.response.CartResponse;
import com.sujula.dto.response.OrderAdminDto;
import com.sujula.exception.BadRequestException;
import com.sujula.exception.ResourceNotFoundException;
import com.sujula.model.*;
import com.sujula.model.enums.OrderStatus;
import com.sujula.model.enums.VendorOrderStatus;
import com.sujula.repository.*;
import com.sujula.service.CurrencyConversionService;
import com.sujula.service.EmailService;
import com.sujula.service.NotificationService;
import com.sujula.service.OrderService;
import com.sujula.service.PushNotificationService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    // ── Statuses from which an order can no longer be cancelled ───────────────
    private static final Set<OrderStatus> TERMINAL_STATUSES = Set.of(
            OrderStatus.SHIPPED, OrderStatus.DELIVERED,
            OrderStatus.CANCELLED, OrderStatus.REFUNDED);

    private static final Set<OrderStatus> CUSTOMER_CANCELLABLE = Set.of(
            OrderStatus.PENDING, OrderStatus.CONFIRMED);

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final OrderRepository              orderRepository;
    private final ProductRepository            productRepository;
    private final ProductVariantRepository     variantRepository;
    private final UserRepository               userRepository;
    private final AddressRepository            addressRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final VendorOrderRepository        vendorOrderRepository;
    private final CartRepository               cartRepository;
    private final CouponRepository             couponRepository;
    private final CouponUsageRepository        couponUsageRepository;
    private final CurrencyConversionService    currencyConversionService;
    private final EmailService                 emailService;
    private final NotificationService          notificationService;
    private final PushNotificationService      pushNotificationService;

    // ─────────────────────────────────────────────────────────────────────────
    // Create from explicit request
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order create(Long customerId, CreateOrderRequest request) {
        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", customerId));

        Address address = addressRepository.findById(request.getShippingAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address", request.getShippingAddressId()));

        if (!address.getUser().getId().equals(customerId)) {
            throw new BadRequestException("Address does not belong to this user");
        }

        // ── 1. Resolve items, validate stock, deduct inventory ────────────────
        List<OrderItem> allItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            // Acquire a row-level exclusive lock before reading stock.
            // This prevents two concurrent orders from both seeing the same available
            // stock and both deducting it — the second thread blocks on the DB lock
            // until the first commits, then reads the already-reduced quantity.
            Product product = productRepository.findByIdForUpdate(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemReq.getProductId()));

            if (!product.isActive()) {
                throw new BadRequestException("Product is no longer available: " + product.getName());
            }

            // ── Variant handling ──────────────────────────────────────────────
            ProductVariant variant = null;
            BigDecimal unitPrice   = product.getPrice();
            String variantSku      = null;
            String selectedOptions = null;

            if (itemReq.getVariantId() != null) {
                // Lock the variant row too — concurrent orders on the same variant
                // must serialize to prevent variant-level oversell.
                variant = variantRepository.findByIdForUpdate(itemReq.getVariantId())
                        .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", itemReq.getVariantId()));

                if (!variant.getProduct().getId().equals(product.getId())) {
                    throw new BadRequestException("Variant does not belong to product: " + product.getName());
                }
                if (!variant.isAvailable()) {
                    throw new BadRequestException("Selected variant is no longer available for: " + product.getName());
                }
                if (variant.getStockQuantity() < itemReq.getQuantity()) {
                    throw new BadRequestException("Insufficient variant stock for: " + product.getName()
                            + " (available: " + variant.getStockQuantity() + ")");
                }
                if (variant.getPrice() != null) {
                    unitPrice = variant.getPrice();
                }
                variantSku = variant.getSku();

                // Build human-readable option string e.g. "Size: Large, Color: Red"
                if (variant.getValues() != null && !variant.getValues().isEmpty()) {
                    selectedOptions = variant.getValues().stream()
                            .map(v -> v.getOptionValue().getOption().getName()
                                      + ": " + v.getOptionValue().getDisplayValue())
                            .collect(Collectors.joining(", "));
                }

                // Deduct variant stock
                variant.setStockQuantity(variant.getStockQuantity() - itemReq.getQuantity());
                variantRepository.save(variant);
            } else {
                // Product-level stock
                if (product.getStockQuantity() < itemReq.getQuantity()) {
                    throw new BadRequestException("Insufficient stock for: " + product.getName()
                            + " (available: " + product.getStockQuantity() + ")");
                }
                product.setStockQuantity(product.getStockQuantity() - itemReq.getQuantity());
                productRepository.save(product);
            }

            BigDecimal itemTotal = unitPrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            subtotal = subtotal.add(itemTotal);

            String thumbnail = product.getImages().stream()
                    .filter(ProductImage::isDefault)
                    .findFirst()
                    .or(() -> product.getImages().stream().findFirst())
                    .map(ProductImage::getImageUrl)
                    .orElse(null);

            allItems.add(OrderItem.builder()
                    .product(product)
                    .variant(variant)
                    .vendor(product.getVendor())
                    .quantity(itemReq.getQuantity())
                    .unitPrice(unitPrice)
                    .totalPrice(itemTotal)
                    .productName(product.getName())
                    .productSku(product.getSku())
                    .variantSku(variantSku)
                    .selectedOptions(selectedOptions)
                    .productImageUrl(thumbnail)
                    .build());
        }

        // ── 2. Resolve coupon discount ────────────────────────────────────────
        Coupon appliedCoupon  = null;
        BigDecimal discount   = BigDecimal.ZERO;

        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            appliedCoupon = couponRepository
                    .findByCode(request.getCouponCode().toUpperCase().trim())
                    .orElseThrow(() -> new BadRequestException("Invalid coupon code"));

            validateCoupon(appliedCoupon, customerId, subtotal);
            discount = computeDiscount(appliedCoupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount);

        // ── 3. Build parent Order ─────────────────────────────────────────────
        Order order = Order.builder()
                .orderNumber("SJL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .customer(customer)
                .status(OrderStatus.PENDING)
                .subtotal(subtotal)
                .shippingCost(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .discount(discount)
                .total(total)
                .currency("GMD")
                .coupon(appliedCoupon)
                .couponCode(appliedCoupon != null ? appliedCoupon.getCode() : null)
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

        Order savedOrder = orderRepository.save(order);

        // ── 4. Assign items to parent order ───────────────────────────────────
        allItems.forEach(item -> item.setOrder(savedOrder));

        // ── 5. Group by vendor → one VendorOrder per vendor ───────────────────
        Map<Long, List<OrderItem>> byVendor = allItems.stream()
                .collect(Collectors.groupingBy(i -> i.getVendor().getId()));

        List<VendorOrder> vendorOrders = new ArrayList<>();
        for (List<OrderItem> vendorItems : byVendor.values()) {
            BigDecimal vendorSubtotal = vendorItems.stream()
                    .map(OrderItem::getTotalPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            VendorOrder vendorOrder = VendorOrder.builder()
                    .order(savedOrder)
                    .vendor(vendorItems.get(0).getVendor())
                    .subtotal(vendorSubtotal)
                    .status(VendorOrderStatus.PENDING)
                    .build();

            VendorOrder savedVendorOrder = vendorOrderRepository.save(vendorOrder);
            vendorItems.forEach(item -> item.setVendorOrder(savedVendorOrder));
            vendorOrders.add(savedVendorOrder);
        }

        savedOrder.setItems(allItems);
        Order finalOrder = orderRepository.save(savedOrder);

        // ── 6. Record coupon usage + increment counter ────────────────────────
        if (appliedCoupon != null) {
            couponUsageRepository.save(CouponUsage.builder()
                    .coupon(appliedCoupon)
                    .user(customer)
                    .order(finalOrder)
                    .build());
            appliedCoupon.setUsageCount(appliedCoupon.getUsageCount() + 1);
            couponRepository.save(appliedCoupon);
        }

        // ── 7. Post-creation side-effects (fire-and-forget) ───────────────────
        dispatchOrderCreationEvents(customer, finalOrder, vendorOrders);

        return finalOrder;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Authenticated checkout from frontend payload (inline address)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order createUserOrder(Long userId, UserCheckoutRequest request) {
        User customer = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // ── 1. Resolve items, validate stock, deduct inventory ────────────────
        List<OrderItem> allItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        // Resolve checkout currency FIRST — needed inside the item loop for price conversion
        String effectiveCurrency = (request.getCurrency() != null && !request.getCurrency().isBlank())
                ? request.getCurrency().toUpperCase() : "GMD";

        for (UserCheckoutRequest.ItemRequest itemReq : request.getItems()) {
            Product product = productRepository.findByIdForUpdate(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemReq.getProductId()));

            if (!product.isActive()) {
                throw new BadRequestException("Product is no longer available: " + product.getName());
            }

            ProductVariant variant        = null;
            BigDecimal     unitPrice      = product.getPrice();
            String         variantSku     = null;
            String         selectedOpts   = null;

            if (itemReq.getVariantId() != null) {
                variant = variantRepository.findByIdForUpdate(itemReq.getVariantId())
                        .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", itemReq.getVariantId()));
                if (!variant.getProduct().getId().equals(product.getId())) {
                    throw new BadRequestException("Variant does not belong to product: " + product.getName());
                }
                if (!variant.isAvailable()) {
                    throw new BadRequestException("Selected variant is no longer available for: " + product.getName());
                }
                if (variant.getStockQuantity() < itemReq.getQuantity()) {
                    throw new BadRequestException("Insufficient variant stock for: " + product.getName()
                            + " (available: " + variant.getStockQuantity() + ")");
                }
                if (variant.getPrice() != null) unitPrice = variant.getPrice();
                variantSku = variant.getSku();
                if (variant.getValues() != null && !variant.getValues().isEmpty()) {
                    selectedOpts = variant.getValues().stream()
                            .map(v -> v.getOptionValue().getOption().getName()
                                    + ": " + v.getOptionValue().getDisplayValue())
                            .collect(Collectors.joining(", "));
                }
                variant.setStockQuantity(variant.getStockQuantity() - itemReq.getQuantity());
                variantRepository.save(variant);
            } else {
                if (product.getStockQuantity() < itemReq.getQuantity()) {
                    throw new BadRequestException("Insufficient stock for: " + product.getName()
                            + " (available: " + product.getStockQuantity() + ")");
                }
                product.setStockQuantity(product.getStockQuantity() - itemReq.getQuantity());
                productRepository.save(product);
            }

            // Convert unit price to checkout currency (all product prices are stored in GMD)
            if (!"GMD".equals(effectiveCurrency)) {
                unitPrice = currencyConversionService.convert(unitPrice, "GMD", effectiveCurrency)
                        .setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal itemTotal = unitPrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            subtotal = subtotal.add(itemTotal);

            String thumbnail = product.getImages().stream()
                    .filter(ProductImage::isDefault)
                    .findFirst()
                    .or(() -> product.getImages().stream().findFirst())
                    .map(ProductImage::getImageUrl)
                    .orElse(null);

            allItems.add(OrderItem.builder()
                    .product(product)
                    .variant(variant)
                    .vendor(product.getVendor())
                    .quantity(itemReq.getQuantity())
                    .unitPrice(unitPrice)
                    .totalPrice(itemTotal)
                    .productName(product.getName())
                    .productSku(product.getSku())
                    .variantSku(variantSku)
                    .selectedOptions(selectedOpts)
                    .productImageUrl(thumbnail)
                    .build());
        }

        // ── 2. Resolve coupon ─────────────────────────────────────────────────
        Coupon     appliedCoupon = null;
        BigDecimal discount      = BigDecimal.ZERO;

        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            appliedCoupon = couponRepository
                    .findByCode(request.getCouponCode().toUpperCase().trim())
                    .orElseThrow(() -> new BadRequestException("Invalid coupon code"));
            validateCoupon(appliedCoupon, userId, subtotal);
            discount = computeDiscount(appliedCoupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount);

        // ── 3. Resolve address fields ─────────────────────────────────────────
        UserCheckoutRequest.RecipientInfo r = request.getRecipient();
        String shippingStreet = request.getDeliveryAddress() != null
                ? request.getDeliveryAddress().getAddress()
                : (request.getPickupPointId() != null
                        ? "Pickup Point #" + request.getPickupPointId()
                        : "");

        // ── 4. Build Order ────────────────────────────────────────────────────
        Order order = Order.builder()
                .orderNumber("SJL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .customer(customer)
                .status(OrderStatus.PENDING)
                .subtotal(subtotal)
                .shippingCost(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .discount(discount)
                .total(total)
                .currency(effectiveCurrency)
                .coupon(appliedCoupon)
                .couponCode(appliedCoupon != null ? appliedCoupon.getCode() : null)
                .shippingFullName(r.getName())
                .shippingPhone(r.getPhone())
                .shippingStreet(shippingStreet)
                .shippingCity(r.getCity())
                .shippingState(r.getRegion())
                .shippingCountry(r.getCountry())
                .notes(request.getNotes())
                .build();

        Order savedOrder = orderRepository.save(order);

        // ── 5. Assign items + group by vendor ─────────────────────────────────
        allItems.forEach(item -> item.setOrder(savedOrder));

        Map<Long, List<OrderItem>> byVendor = allItems.stream()
                .collect(Collectors.groupingBy(i -> i.getVendor().getId()));

        List<VendorOrder> vendorOrders = new ArrayList<>();
        for (List<OrderItem> vendorItems : byVendor.values()) {
            BigDecimal vendorSubtotal = vendorItems.stream()
                    .map(OrderItem::getTotalPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            VendorOrder vo = VendorOrder.builder()
                    .order(savedOrder)
                    .vendor(vendorItems.get(0).getVendor())
                    .subtotal(vendorSubtotal)
                    .status(VendorOrderStatus.PENDING)
                    .build();

            VendorOrder savedVo = vendorOrderRepository.save(vo);
            vendorItems.forEach(item -> item.setVendorOrder(savedVo));
            vendorOrders.add(savedVo);
        }

        savedOrder.setItems(allItems);
        Order finalOrder = orderRepository.save(savedOrder);

        // ── 6. Record coupon usage ────────────────────────────────────────────
        if (appliedCoupon != null) {
            couponUsageRepository.save(CouponUsage.builder()
                    .coupon(appliedCoupon)
                    .user(customer)
                    .order(finalOrder)
                    .build());
            appliedCoupon.setUsageCount(appliedCoupon.getUsageCount() + 1);
            couponRepository.save(appliedCoupon);
        }

        // ── 7. Post-creation side-effects ─────────────────────────────────────
        dispatchOrderCreationEvents(customer, finalOrder, vendorOrders);

        return finalOrder;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Checkout from cart
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order createFromCart(Long userId, Long shippingAddressId, String notes) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("No active cart found"));

        if (cart.getItems().isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        // Build CreateOrderRequest from cart contents
        List<CreateOrderRequest.OrderItemRequest> itemRequests = cart.getItems().stream()
                .map(cartItem -> {
                    CreateOrderRequest.OrderItemRequest r = new CreateOrderRequest.OrderItemRequest();
                    r.setProductId(cartItem.getProduct().getId());
                    r.setVariantId(cartItem.getVariant() != null ? cartItem.getVariant().getId() : null);
                    r.setQuantity(cartItem.getQuantity());
                    return r;
                })
                .toList();

        CreateOrderRequest req = new CreateOrderRequest();
        req.setItems(itemRequests);
        req.setShippingAddressId(shippingAddressId);
        req.setCouponCode(cart.getCoupon() != null ? cart.getCoupon().getCode() : null);
        req.setNotes(notes);

        // Delegate to create() — all validation, stock deduction, coupon logic handled there
        Order order = create(userId, req);

        // Clear cart after successful checkout
        cart.getItems().clear();
        cart.setCoupon(null);
        cartRepository.save(cart);

        return order;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public OrderAdminDto findById(Long id) {
        // JOIN FETCH customer + items + product/vendor/vendorOrder per item
        // so DTO mapping runs inside the transaction with all proxies initialised.
        Order order = orderRepository.findByIdFetchAll(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
        return OrderAdminDto.toAdminDto(order);
    }

    @Override
    public Order findByOrderNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderNumber));
    }

    @Override
    public Page<Order> findByCustomer(Long customerId, Pageable pageable) {
        return orderRepository.findByCustomerId(customerId, pageable);
    }

    @Override
    public Page<Order> findByVendor(Long vendorId, Pageable pageable) {
        return orderRepository.findByItems_VendorId(vendorId, pageable);
    }

    @Override
    public Page<Order> findByStatus(OrderStatus status, Pageable pageable) {
        // JOIN FETCH customer so admin page can display customer name/email
        return orderRepository.findByStatusFetchCustomer(status, pageable);
    }

    @Override
    public Page<Order> findAll(Pageable pageable) {
        // JOIN FETCH customer so admin page can display customer name/email
        return orderRepository.findAllFetchCustomer(pageable);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guest checkout
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Order createGuestOrder(GuestCheckoutRequest request) {

        // ── 1. Resolve items from guest cart OR explicit list ─────────────────
        List<CreateOrderRequest.OrderItemRequest> itemRequests;
        Cart guestCart = null;

        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            guestCart = cartRepository.findBySessionId(request.getSessionId())
                    .orElseThrow(() -> new BadRequestException(
                        "No active guest cart found. Please add items to your cart first."));
            if (guestCart.getItems().isEmpty()) {
                throw new BadRequestException("Your cart is empty");
            }
            // Build item requests from cart
            final Cart fc = guestCart;
            itemRequests = fc.getItems().stream()
                    .map(ci -> {
                        CreateOrderRequest.OrderItemRequest r = new CreateOrderRequest.OrderItemRequest();
                        r.setProductId(ci.getProduct().getId());
                        r.setVariantId(ci.getVariant() != null ? ci.getVariant().getId() : null);
                        r.setQuantity(ci.getQuantity());
                        return r;
                    })
                    .toList();
            // Use cart coupon if request doesn't specify one
            if ((request.getCouponCode() == null || request.getCouponCode().isBlank())
                    && guestCart.getCoupon() != null) {
                request.setCouponCode(guestCart.getCoupon().getCode());
            }
        } else {
            if (request.getItems() == null || request.getItems().isEmpty()) {
                throw new BadRequestException(
                    "Provide either a sessionId (guest cart) or an explicit items list");
            }
            itemRequests = request.getItems();
        }

        // ── 2. Resolve products, validate stock, deduct inventory ─────────────
        List<OrderItem> allItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        // Resolve checkout currency FIRST — needed inside the item loop for price conversion
        String effectiveCurrency = (request.getCurrency() != null && !request.getCurrency().isBlank())
                ? request.getCurrency().toUpperCase() : "GMD";

        for (CreateOrderRequest.OrderItemRequest itemReq : itemRequests) {
            // Pessimistic lock — same race-condition fix as the authenticated order path
            Product product = productRepository.findByIdForUpdate(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemReq.getProductId()));
            if (!product.isActive()) {
                throw new BadRequestException("Product is no longer available: " + product.getName());
            }

            ProductVariant variant        = null;
            BigDecimal     unitPrice      = product.getPrice();
            String         variantSku     = null;
            String         selectedOptions = null;

            if (itemReq.getVariantId() != null) {
                variant = variantRepository.findByIdForUpdate(itemReq.getVariantId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                            "ProductVariant", itemReq.getVariantId()));
                if (!variant.getProduct().getId().equals(product.getId())) {
                    throw new BadRequestException(
                        "Variant does not belong to product: " + product.getName());
                }
                if (!variant.isAvailable()) {
                    throw new BadRequestException(
                        "Selected variant is no longer available for: " + product.getName());
                }
                if (variant.getStockQuantity() < itemReq.getQuantity()) {
                    throw new BadRequestException("Insufficient variant stock for: "
                        + product.getName() + " (available: " + variant.getStockQuantity() + ")");
                }
                if (variant.getPrice() != null) unitPrice = variant.getPrice();
                variantSku = variant.getSku();
                if (variant.getValues() != null && !variant.getValues().isEmpty()) {
                    selectedOptions = variant.getValues().stream()
                            .map(v -> v.getOptionValue().getOption().getName()
                                    + ": " + v.getOptionValue().getDisplayValue())
                            .collect(Collectors.joining(", "));
                }
                variant.setStockQuantity(variant.getStockQuantity() - itemReq.getQuantity());
                variantRepository.save(variant);
            } else {
                if (product.getStockQuantity() < itemReq.getQuantity()) {
                    throw new BadRequestException("Insufficient stock for: " + product.getName()
                        + " (available: " + product.getStockQuantity() + ")");
                }
                product.setStockQuantity(product.getStockQuantity() - itemReq.getQuantity());
                productRepository.save(product);
            }

            // Convert unit price to checkout currency (all product prices are stored in GMD)
            if (!"GMD".equals(effectiveCurrency)) {
                unitPrice = currencyConversionService.convert(unitPrice, "GMD", effectiveCurrency)
                        .setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal itemTotal = unitPrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            subtotal = subtotal.add(itemTotal);

            String thumbnail = product.getImages().stream()
                    .filter(ProductImage::isDefault)
                    .findFirst()
                    .or(() -> product.getImages().stream().findFirst())
                    .map(ProductImage::getImageUrl)
                    .orElse(null);

            allItems.add(OrderItem.builder()
                    .product(product)
                    .variant(variant)
                    .vendor(product.getVendor())
                    .quantity(itemReq.getQuantity())
                    .unitPrice(unitPrice)
                    .totalPrice(itemTotal)
                    .productName(product.getName())
                    .productSku(product.getSku())
                    .variantSku(variantSku)
                    .selectedOptions(selectedOptions)
                    .productImageUrl(thumbnail)
                    .build());
        }

        // ── 3. Resolve coupon discount (no per-user limit for guests) ─────────
        Coupon     appliedCoupon = null;
        BigDecimal discount      = BigDecimal.ZERO;

        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            appliedCoupon = couponRepository
                    .findByCode(request.getCouponCode().toUpperCase().trim())
                    .orElseThrow(() -> new BadRequestException("Invalid coupon code"));
            // Validate without per-user limit (null userId)
            validateCoupon(appliedCoupon, null, subtotal);
            discount = computeDiscount(appliedCoupon, subtotal);
        }

        BigDecimal total = subtotal.subtract(discount);

        // ── 4. Build Order (customer = null → guest order) ────────────────────
        Order order = Order.builder()
                .orderNumber("SJL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .customer(null)                              // guest order
                .guestName(request.getGuestName())
                .guestEmail(request.getGuestEmail().toLowerCase().trim())
                .guestPhone(request.getGuestPhone())
                .guestSessionId(request.getSessionId())
                .status(OrderStatus.PENDING)
                .subtotal(subtotal)
                .shippingCost(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .discount(discount)
                .total(total)
                .currency(effectiveCurrency)
                .coupon(appliedCoupon)
                .couponCode(appliedCoupon != null ? appliedCoupon.getCode() : null)
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

        Order savedOrder = orderRepository.save(order);

        // ── 5. Assign items and group into VendorOrders ───────────────────────
        allItems.forEach(item -> item.setOrder(savedOrder));

        Map<Long, List<OrderItem>> byVendor = allItems.stream()
                .collect(Collectors.groupingBy(i -> i.getVendor().getId()));

        List<VendorOrder> vendorOrders = new ArrayList<>();
        for (List<OrderItem> vendorItems : byVendor.values()) {
            BigDecimal vendorSubtotal = vendorItems.stream()
                    .map(OrderItem::getTotalPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            VendorOrder vo = VendorOrder.builder()
                    .order(savedOrder)
                    .vendor(vendorItems.get(0).getVendor())
                    .subtotal(vendorSubtotal)
                    .status(VendorOrderStatus.PENDING)
                    .build();

            VendorOrder savedVo = vendorOrderRepository.save(vo);
            vendorItems.forEach(item -> item.setVendorOrder(savedVo));
            vendorOrders.add(savedVo);
        }

        savedOrder.setItems(allItems);
        Order finalOrder = orderRepository.save(savedOrder);

        // ── 6. Record coupon usage (no user link for guest) ───────────────────
        if (appliedCoupon != null) {
            // CouponUsage.user is nullable for guest orders
            couponUsageRepository.save(CouponUsage.builder()
                    .coupon(appliedCoupon)
                    .user(null)
                    .order(finalOrder)
                    .build());
            appliedCoupon.setUsageCount(appliedCoupon.getUsageCount() + 1);
            couponRepository.save(appliedCoupon);
        }

        // ── 7. Clear guest cart ───────────────────────────────────────────────
        if (guestCart != null) {
            guestCart.getItems().clear();
            guestCart.setCoupon(null);
            cartRepository.save(guestCart);
        }

        // ── 8. Send confirmation to guest email ───────────────────────────────
        dispatchGuestOrderCreationEvents(request.getGuestEmail(),
                request.getGuestName(), finalOrder, vendorOrders);

        return finalOrder;
    }

    @Override
    @Transactional(readOnly = true)
    public Order findGuestOrder(String orderNumber, String guestEmail) {
        return orderRepository.findByOrderNumberAndGuestEmailIgnoreCase(orderNumber, guestEmail)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Order not found. Please check your order number and email."));
    }

    @Override
    public List<OrderStatusHistory> getStatusHistory(Long orderId) {
        findById(orderId);
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
        Order order      = orderRepository.findById(orderId).orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
        OrderStatus from = order.getStatus();
        OrderStatus to   = request.getStatus();

        // Guard: terminal statuses can't transition (except CANCELLED → REFUNDED)
        if (TERMINAL_STATUSES.contains(from)) {
            if (!(from == OrderStatus.CANCELLED && to == OrderStatus.REFUNDED)) {
                throw new BadRequestException(
                        "Cannot change order status from " + from + " to " + to);
            }
        }

        // Restore stock + cancel all vendor sub-orders on cancellation
        if (to == OrderStatus.CANCELLED && from != OrderStatus.CANCELLED) {
            restoreStock(order);
            cancelVendorOrders(order);
        }

        order.setStatus(to);
        if (request.getNotes() != null) order.setNotes(request.getNotes());
        Order saved = orderRepository.save(order);

        User changedBy = Optional.ofNullable(adminUserId)
                .flatMap(userRepository::findById)
                .orElse(null);

        statusHistoryRepository.save(OrderStatusHistory.builder()
                .order(saved)
                .fromStatus(from)
                .toStatus(to)
                .notes(request.getNotes())
                .changedBy(changedBy)
                .build());

        // ── Push notification to customer on status change ────────────────────
        if (saved.getCustomer() != null) {
            try {
                String pushTitle = orderStatusPushTitle(to);
                String pushBody  = "Order " + saved.getOrderNumber() + " — " + orderStatusPushBody(to);
                pushNotificationService.sendToUser(
                        saved.getCustomer().getId(),
                        pushTitle,
                        pushBody,
                        Map.of("type",        "ORDER_STATUS_CHANGED",
                               "orderId",     String.valueOf(saved.getId()),
                               "orderNumber", saved.getOrderNumber(),
                               "status",      to.name()));
            } catch (Exception ignored) { /* never block the transaction */ }
        }

        return saved;
    }

    private static String orderStatusPushTitle(OrderStatus status) {
        return switch (status) {
            case CONFIRMED  -> "Order Confirmed ✅";
            case PROCESSING -> "Order Being Prepared 🏭";
            case SHIPPED    -> "Order Shipped 🚚";
            case DELIVERED  -> "Order Delivered 🎉";
            case CANCELLED  -> "Order Cancelled";
            case REFUNDED   -> "Refund Processed 💸";
            default         -> "Order Update";
        };
    }

    private static String orderStatusPushBody(OrderStatus status) {
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

    @Override
    @Transactional
    public Order cancelByCustomer(Long orderId, Long customerId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));

        // Guest orders cannot be cancelled through the authenticated endpoint
        if (order.isGuestOrder()) {
            throw new BadRequestException("Guest orders cannot be cancelled through this endpoint");
        }
        if (!order.getCustomer().getId().equals(customerId)) {
            throw new BadRequestException("Order does not belong to this user");
        }
        if (!CUSTOMER_CANCELLABLE.contains(order.getStatus())) {
            throw new BadRequestException(
                    "Order cannot be cancelled at this stage. "
                    + "Current status: " + order.getStatus()
                    + ". Only PENDING or CONFIRMED orders may be cancelled by the customer.");
        }

        UpdateOrderStatusRequest req = new UpdateOrderStatusRequest();
        req.setStatus(OrderStatus.CANCELLED);
        req.setNotes("Cancelled by customer");
        return updateStatus(orderId, req);
    }

    @Override
    @Transactional
    public Order cancelGuestOrder(String orderNumber, String guestEmail) {
        Order order = findGuestOrder(orderNumber, guestEmail);

        if (!CUSTOMER_CANCELLABLE.contains(order.getStatus())) {
            throw new BadRequestException(
                    "Order cannot be cancelled at this stage. "
                    + "Current status: " + order.getStatus()
                    + ". Only PENDING or CONFIRMED orders may be cancelled.");
        }

        UpdateOrderStatusRequest req = new UpdateOrderStatusRequest();
        req.setStatus(OrderStatus.CANCELLED);
        req.setNotes("Cancelled by guest");
        return updateStatus(order.getId(), req);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Validate a coupon before applying it to an order. */
    private void validateCoupon(Coupon coupon, Long userId, BigDecimal subtotal) {
        if (!coupon.isActive()) {
            throw new BadRequestException("Coupon is no longer active");
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStartsAt() != null && now.isBefore(coupon.getStartsAt())) {
            throw new BadRequestException("Coupon is not yet valid");
        }
        if (coupon.getExpiresAt() != null && now.isAfter(coupon.getExpiresAt())) {
            throw new BadRequestException("Coupon has expired");
        }
        if (coupon.getMinimumOrderAmount() != null
                && subtotal.compareTo(coupon.getMinimumOrderAmount()) < 0) {
            throw new BadRequestException(
                    "Minimum order amount for this coupon is " + coupon.getMinimumOrderAmount());
        }
        if (coupon.getUsageLimit() != null && coupon.getUsageCount() >= coupon.getUsageLimit()) {
            throw new BadRequestException("Coupon usage limit has been reached");
        }
        // Skip per-user limit for guest orders (userId == null) — no account to track against
        if (coupon.getPerUserLimit() != null && userId != null) {
            long used = couponUsageRepository.countByCouponIdAndUserId(coupon.getId(), userId);
            if (used >= coupon.getPerUserLimit()) {
                throw new BadRequestException(
                        "You have already used this coupon the maximum number of times");
            }
        }
    }

    /** Compute the discount amount for a coupon against a given subtotal. */
    private BigDecimal computeDiscount(Coupon coupon, BigDecimal subtotal) {
        return switch (coupon.getType()) {
            case PERCENTAGE -> {
                BigDecimal d = subtotal
                        .multiply(coupon.getValue())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                if (coupon.getMaximumDiscountAmount() != null) {
                    d = d.min(coupon.getMaximumDiscountAmount());
                }
                yield d;
            }
            case FIXED_AMOUNT -> coupon.getValue().min(subtotal);
            // FREE_SHIPPING discount is applied to shipping cost, not subtotal
            case FREE_SHIPPING -> BigDecimal.ZERO;
        };
    }

    /** Restore stock for every item in a cancelled order. */
    private void restoreStock(Order order) {
        for (OrderItem item : order.getItems()) {
            if (item.getVariant() != null) {
                ProductVariant v = item.getVariant();
                v.setStockQuantity(v.getStockQuantity() + item.getQuantity());
                variantRepository.save(v);
            } else {
                Product p = item.getProduct();
                p.setStockQuantity(p.getStockQuantity() + item.getQuantity());
                productRepository.save(p);
            }
        }
    }

    /** Cancel every vendor sub-order that is not already terminal. */
    private void cancelVendorOrders(Order order) {
        Set<VendorOrderStatus> vendorTerminal = Set.of(
                VendorOrderStatus.DELIVERED, VendorOrderStatus.CANCELLED,
                VendorOrderStatus.REFUNDED);

        for (VendorOrder vo : vendorOrderRepository.findByOrderId(order.getId())) {
            if (!vendorTerminal.contains(vo.getStatus())) {
                vo.setStatus(VendorOrderStatus.CANCELLED);
                vo.setCancelledAt(LocalDateTime.now());
                vendorOrderRepository.save(vo);
            }
        }
    }

    /**
     * Fire-and-forget vendor push after a guest order is placed.
     * Customer confirmation email is intentionally NOT sent here — it is deferred
     * to {@code PaymentServiceImpl.advanceOrderOnPayment} so the guest only
     * receives "Order Confirmed" after payment is actually verified.
     */
    private void dispatchGuestOrderCreationEvents(String guestEmail, String guestName,
                                                   Order order, List<VendorOrder> vendorOrders) {
        try {
            for (VendorOrder vo : vendorOrders) {
                Vendor vendor = vo.getVendor();
                if (vendor.getUser() != null) {
                    emailService.sendVendorOrderNotification(
                            vendor.getUser().getEmail(),
                            vendor.getStoreName(),
                            order.getOrderNumber());

                    notificationService.send(
                            vendor.getUser().getId(),
                            "New Guest Order Received",
                            "A new guest order (" + order.getOrderNumber() + ") requires fulfilment.",
                            "ORDER", order.getOrderNumber());

                    pushNotificationService.sendToUser(
                            vendor.getUser().getId(),
                            "🛒 New Guest Order!",
                            "Guest order " + order.getOrderNumber() + " — "
                                    + vo.getItems().size() + " item(s) ready to fulfil.",
                            Map.of("type", "NEW_ORDER",
                                   "orderId",     String.valueOf(order.getId()),
                                   "orderNumber", order.getOrderNumber()));
                }
            }
        } catch (Exception ignored) {
            // Never let notifications roll back the order transaction
        }
    }

    /**
     * Fire-and-forget in-app notifications + vendor push after order creation.
     * Customer confirmation email is intentionally NOT sent here — it is deferred
     * to {@code PaymentServiceImpl.advanceOrderOnPayment} so the customer only
     * receives "Order Confirmed" after payment is actually verified.
     */
    private void dispatchOrderCreationEvents(User customer, Order order,
                                              List<VendorOrder> vendorOrders) {
        try {
            // ── Customer: order placed push ───────────────────────────────────
            notificationService.send(
                    customer.getId(),
                    "Order Placed Successfully",
                    "Your order " + order.getOrderNumber() + " has been placed and is being processed.",
                    "ORDER", order.getOrderNumber());

            pushNotificationService.sendToUser(
                    customer.getId(),
                    "Order Placed ✅",
                    "Your order " + order.getOrderNumber() + " is confirmed and being processed.",
                    Map.of("type", "ORDER_PLACED", "orderId", String.valueOf(order.getId()),
                           "orderNumber", order.getOrderNumber()));

            // ── Vendor: new order push ────────────────────────────────────────
            for (VendorOrder vo : vendorOrders) {
                Vendor vendor = vo.getVendor();
                if (vendor.getUser() != null) {
                    emailService.sendVendorOrderNotification(
                            vendor.getUser().getEmail(),
                            vendor.getStoreName(),
                            order.getOrderNumber());

                    notificationService.send(
                            vendor.getUser().getId(),
                            "New Order Received",
                            "You have a new order (" + order.getOrderNumber() + ") with "
                                    + vo.getItems().size() + " item(s) to fulfil.",
                            "ORDER", order.getOrderNumber());

                    pushNotificationService.sendToUser(
                            vendor.getUser().getId(),
                            "🛒 New Order!",
                            "Order " + order.getOrderNumber() + " — "
                                    + vo.getItems().size() + " item(s) ready to fulfil.",
                            Map.of("type", "NEW_ORDER",
                                   "orderId",     String.valueOf(order.getId()),
                                   "orderNumber", order.getOrderNumber()));
                }
            }
        } catch (Exception ignored) {
            // Never let notifications roll back the order transaction
        }
    }

    @Override
    public Order updateSchedule(String orderNumber, Long userId, OrderScheduleRequest request) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'updateSchedule'");
    }

    @Override
    public CartResponse reorder(String orderNumber, Long userId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'reorder'");
    }
}
