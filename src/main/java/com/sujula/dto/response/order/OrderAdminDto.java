package com.sujula.dto.response.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin-facing view of an order: the flattened customer/guest identity the
 * admin list needs, plus the same per-vendor native/display currency split
 * that drives the storefront cart, so a support agent can see exactly what
 * each vendor is owed without cross-referencing separate screens.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderAdminDto {

    private Long id;
    private String orderNumber;
    private boolean guest;

    private Long customerId;
    private String customerName;
    private String customerEmail;

    private OrderStatus status;
    private String currency;

    private BigDecimal subtotal;
    private BigDecimal shippingCost;
    private BigDecimal taxAmount;
    private BigDecimal discount;
    private BigDecimal total;

    private String couponCode;

    private String shippingFullName;
    private String shippingPhone;
    private String shippingStreet;
    private String shippingApartment;
    private String shippingCity;
    private String shippingState;
    private String shippingPostalCode;
    private String shippingCountry;

    private String paymentStatus;
    private String paymentMethod;

    private String notes;

    private List<OrderItemDto> items;
    private List<VendorOrderDto> vendorOrders;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static OrderAdminDto toAdminDto(Order order) {
        return OrderAdminDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .guest(order.isGuestOrder())
                .customerId(order.getCustomer() != null ? order.getCustomer().getId() : null)
                .customerName(order.getDisplayName())
                .customerEmail(order.getContactEmail())
                .status(order.getStatus())
                .currency(order.getCurrency())
                .subtotal(order.getSubtotal())
                .shippingCost(order.getShippingCost())
                .taxAmount(order.getTaxAmount())
                .discount(order.getDiscount())
                .total(order.getTotal())
                .couponCode(order.getCouponCode())
                .shippingFullName(order.getShippingFullName())
                .shippingPhone(order.getShippingPhone())
                .shippingStreet(order.getShippingStreet())
                .shippingApartment(order.getShippingApartment())
                .shippingCity(order.getShippingCity())
                .shippingState(order.getShippingState())
                .shippingPostalCode(order.getShippingPostalCode())
                .shippingCountry(order.getShippingCountry())
                .paymentStatus(order.getPaymentStatus())
                .paymentMethod(order.getPaymentMethod())
                .notes(order.getNotes())
                .items(order.getItems().stream().map(OrderItemDto::from).toList())
                .vendorOrders(order.getVendorOrders().stream().map(VendorOrderDto::from).toList())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OrderItemDto {
        private Long itemId;
        private Long productId;
        private String productName;
        private String productSku;
        private Long variantId;
        private String variantSku;
        private String selectedOptions;
        private String productImageUrl;
        private Long vendorId;
        private Integer quantity;
        private String currency;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
        private BigDecimal unitPriceConverted;
        private BigDecimal totalPriceConverted;

        static OrderItemDto from(OrderItem item) {
            return OrderItemDto.builder()
                    .itemId(item.getId())
                    .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                    .productName(item.getProductName())
                    .productSku(item.getProductSku())
                    .variantId(item.getVariant() != null ? item.getVariant().getId() : null)
                    .variantSku(item.getVariantSku())
                    .selectedOptions(item.getSelectedOptions())
                    .productImageUrl(item.getProductImageUrl())
                    .vendorId(item.getVendor() != null ? item.getVendor().getId() : null)
                    .quantity(item.getQuantity())
                    .currency(item.getCurrency())
                    .unitPrice(item.getUnitPrice())
                    .totalPrice(item.getTotalPrice())
                    .unitPriceConverted(item.getUnitPriceConverted())
                    .totalPriceConverted(item.getTotalPriceConverted())
                    .build();
        }
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VendorOrderDto {
        private Long vendorOrderId;
        private Long vendorId;
        private String vendorStoreName;
        private VendorOrderStatus status;
        private String nativeCurrency;
        private BigDecimal subtotalNative;
        private BigDecimal discountNative;
        private BigDecimal totalNative;
        private BigDecimal subtotal;
        private BigDecimal discount;
        private BigDecimal total;
        private String couponCode;
        private LocalDateTime cancelledAt;

        static VendorOrderDto from(VendorOrder vo) {
            return VendorOrderDto.builder()
                    .vendorOrderId(vo.getId())
                    .vendorId(vo.getVendor() != null ? vo.getVendor().getId() : null)
                    .vendorStoreName(vo.getVendor() != null ? vo.getVendor().getStoreName() : null)
                    .status(vo.getStatus())
                    .nativeCurrency(vo.getNativeCurrency())
                    .subtotalNative(vo.getSubtotalNative())
                    .discountNative(vo.getDiscountNative())
                    .totalNative(vo.getTotalNative())
                    .subtotal(vo.getSubtotal())
                    .discount(vo.getDiscount())
                    .total(vo.getTotal())
                    .couponCode(vo.getCouponCode())
                    .cancelledAt(vo.getCancelledAt())
                    .build();
        }
    }
}
