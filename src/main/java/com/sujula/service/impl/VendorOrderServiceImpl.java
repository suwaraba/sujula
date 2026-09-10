package com.sujula.service.impl;

import com.sujula.dto.response.vendor.VendorOrderDetailResponse;
import com.sujula.dto.response.vendor.VendorOrderStatsResponse;
import com.sujula.dto.response.vendor.VendorOrderSummaryResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.Vendor;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.VendorOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The vendor side of an order, and nothing else.
 *
 * <p>Every read starts from the authenticated user, resolves their vendor
 * profile, and queries with that vendor id as part of the lookup rather than as
 * a check afterwards — so another seller's slice is never in hand at all. Every
 * amount returned comes from the {@code *Native} columns, frozen at checkout in
 * the vendor's own settlement currency; nothing on this path reads the order's
 * display currency, the buyer, or the shipping address, and no conversion
 * happens here at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorOrderServiceImpl implements VendorOrderService {

    /** Orders sitting in the seller's court. */
    private static final Set<VendorOrderStatus> AWAITING_VENDOR = Set.of(
            VendorOrderStatus.PENDING, VendorOrderStatus.CONFIRMED, VendorOrderStatus.PROCESSING);

    /** Payout that is earned and no longer at risk of cancellation. */
    private static final Set<VendorOrderStatus> EARNED = Set.of(VendorOrderStatus.DELIVERED);

    /** Payout on orders placed but not yet in the buyer's hands. */
    private static final Set<VendorOrderStatus> IN_FLIGHT = Set.of(
            VendorOrderStatus.PENDING, VendorOrderStatus.CONFIRMED,
            VendorOrderStatus.PROCESSING, VendorOrderStatus.SHIPPED);

    private final VendorOrderRepository vendorOrderRepository;
    private final VendorRepository vendorRepository;

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public Page<VendorOrderSummaryResponse> findMyOrders(Long vendorUserId, VendorOrderStatus status,
                                                         Pageable pageable) {
        Vendor vendor = requireVendor(vendorUserId);
        Page<VendorOrder> page = status == null
                ? vendorOrderRepository.findQueueByVendorId(vendor.getId(), pageable)
                : vendorOrderRepository.findQueueByVendorIdAndStatus(vendor.getId(), status, pageable);
        return page.map(vendorOrder -> toSummary(vendorOrder, vendor));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public VendorOrderDetailResponse findMyOrder(Long vendorUserId, Long vendorOrderId) {
        Vendor vendor = requireVendor(vendorUserId);
        return toDetail(requireOwnSlice(vendorOrderId, vendor), vendor);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public VendorOrderDetailResponse updateStatus(Long vendorUserId, Long vendorOrderId, VendorOrderStatus next) {
        if (next == null) {
            throw new BadRequestException("A status is required");
        }
        Vendor vendor = requireVendor(vendorUserId);
        VendorOrder vendorOrder = requireOwnSlice(vendorOrderId, vendor);
        VendorOrderStatus current = vendorOrder.getStatus();

        if (current == next) {
            // Setting the status it already has is how a double-tapped button
            // arrives. Nothing to do, and nothing worth failing over.
            return toDetail(vendorOrder, vendor);
        }
        if (!next.isVendorSettable()) {
            throw new BadRequestException(
                    "A vendor cannot set an order to " + next
                            + ". Delivery is confirmed by the courier and refunds by the platform.");
        }
        if (!current.canTransitionTo(next)) {
            throw new BadRequestException(
                    "An order that is " + current + " cannot become " + next
                            + ". Allowed from here: " + describe(current.allowedNext()));
        }

        vendorOrder.setStatus(next);
        if (next == VendorOrderStatus.CANCELLED) {
            vendorOrder.setCancelledAt(java.time.LocalDateTime.now());
        }
        VendorOrder saved = vendorOrderRepository.save(vendorOrder);

        log.info("[VendorOrder] {} moved {} → {} by vendor {}",
                saved.getId(), current, next, vendor.getId());
        return toDetail(saved, vendor);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public VendorOrderStatsResponse stats(Long vendorUserId) {
        Vendor vendor = requireVendor(vendorUserId);

        Map<VendorOrderStatus, Long> counts = new EnumMap<>(VendorOrderStatus.class);
        for (VendorOrderStatus status : VendorOrderStatus.values()) {
            // Seeded so a dashboard renders every status, not only the ones with
            // orders behind them.
            counts.put(status, 0L);
        }
        for (Object[] row : vendorOrderRepository.countByStatusForVendor(vendor.getId())) {
            counts.put((VendorOrderStatus) row[0], ((Number) row[1]).longValue());
        }

        long awaiting = AWAITING_VENDOR.stream().mapToLong(s -> counts.getOrDefault(s, 0L)).sum();

        return VendorOrderStatsResponse.builder()
                .currency(vendor.getSettlementCurrency())
                .ordersByStatus(counts)
                .awaitingAction(awaiting)
                .earnedToDate(zeroIfNull(vendorOrderRepository.sumPayoutNative(vendor.getId(), EARNED)))
                .inFlight(zeroIfNull(vendorOrderRepository.sumPayoutNative(vendor.getId(), IN_FLIGHT)))
                .build();
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private VendorOrderSummaryResponse toSummary(VendorOrder vendorOrder, Vendor vendor) {
        List<OrderItem> items = vendorOrder.getItems();
        int units = items.stream().mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0).sum();

        return VendorOrderSummaryResponse.builder()
                .id(vendorOrder.getId())
                .orderNumber(vendorOrder.getOrder() != null ? vendorOrder.getOrder().getOrderNumber() : null)
                .status(vendorOrder.getStatus())
                .allowedNextStatuses(vendorSettable(vendorOrder.getStatus()))
                .itemCount(items.size())
                .totalUnits(units)
                .currency(currencyFor(vendorOrder, vendor))
                .goodsTotal(vendorOrder.getTotalNative())
                .commission(vendorOrder.getCommissionNative())
                .payout(vendorOrder.getPayoutNative())
                .placedAt(vendorOrder.getCreatedAt())
                .updatedAt(vendorOrder.getUpdatedAt())
                .build();
    }

    private VendorOrderDetailResponse toDetail(VendorOrder vendorOrder, Vendor vendor) {
        List<VendorOrderDetailResponse.Line> lines = new ArrayList<>();
        for (OrderItem item : vendorOrder.getItems()) {
            Product product = item.getProduct();
            ProductVariant variant = item.getVariant();
            lines.add(VendorOrderDetailResponse.Line.builder()
                    .productId(product != null ? product.getId() : null)
                    .variantId(variant != null ? variant.getId() : null)
                    .productName(item.getProductName())
                    .sku(item.getProductSku())
                    .variantSku(item.getVariantSku())
                    .selectedOptions(item.getSelectedOptions())
                    .imageUrl(item.getProductImageUrl())
                    .quantity(item.getQuantity() != null ? item.getQuantity() : 0)
                    // The vendor's own listing price, never the converted one.
                    .unitPrice(item.getUnitPrice())
                    .lineTotal(item.getTotalPrice())
                    .build());
        }

        return VendorOrderDetailResponse.builder()
                .id(vendorOrder.getId())
                .orderNumber(vendorOrder.getOrder() != null ? vendorOrder.getOrder().getOrderNumber() : null)
                .status(vendorOrder.getStatus())
                .allowedNextStatuses(vendorSettable(vendorOrder.getStatus()))
                .currency(currencyFor(vendorOrder, vendor))
                .goodsSubtotal(vendorOrder.getSubtotalNative())
                .discount(vendorOrder.getDiscountNative())
                .goodsTotal(vendorOrder.getTotalNative())
                .commissionRate(vendorOrder.getCommissionRate())
                .commission(vendorOrder.getCommissionNative())
                .delivery(vendorOrder.getDeliveryNative())
                .payout(vendorOrder.getPayoutNative())
                .couponCode(vendorOrder.getCouponCode())
                .lines(lines)
                .placedAt(vendorOrder.getCreatedAt())
                .updatedAt(vendorOrder.getUpdatedAt())
                .cancelledAt(vendorOrder.getCancelledAt())
                .build();
    }

    /**
     * Only the moves this vendor may actually make.
     *
     * <p>SHIPPED can legally become DELIVERED, but not at the seller's word, so
     * it is not offered here — a UI built from this list cannot render a button
     * the service would refuse.
     */
    private static Set<VendorOrderStatus> vendorSettable(VendorOrderStatus current) {
        if (current == null) {
            return Set.of();
        }
        return current.allowedNext().stream()
                .filter(VendorOrderStatus::isVendorSettable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The slice's own frozen currency, falling back to the vendor's current
     * settlement currency when the slice spanned several listing currencies and
     * recorded none.
     */
    private static String currencyFor(VendorOrder vendorOrder, Vendor vendor) {
        return vendorOrder.getNativeCurrency() != null
                ? vendorOrder.getNativeCurrency()
                : vendor.getSettlementCurrency();
    }

    private static String describe(Set<VendorOrderStatus> statuses) {
        return statuses.isEmpty() ? "nothing — this order is closed" : statuses.toString();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    private Vendor requireVendor(Long vendorUserId) {
        return vendorRepository.findByUserId(vendorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor for user", vendorUserId));
    }

    /**
     * The vendor id goes into the query, so another seller's slice comes back
     * empty rather than being fetched and then refused. Not found, not
     * forbidden: a probe must not confirm the id was real.
     */
    private VendorOrder requireOwnSlice(Long vendorOrderId, Vendor vendor) {
        return vendorOrderRepository.findByIdAndVendorId(vendorOrderId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("VendorOrder", vendorOrderId));
    }
}
