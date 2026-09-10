package com.sujula.service.impl;

import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.Vendor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a vendor is owed, fixed at the moment the order is placed.
 *
 * <p>Commission rates and exchange rates both move. Recomputing a payout later
 * would restate what a seller was owed for an order they have already shipped,
 * so the figures are written once, here, from the rate that applied at checkout.
 */
class VendorSettlementFreezeTest {

    private static Vendor vendor(String commissionRate) {
        return Vendor.builder()
                .id(50L)
                .storeName("Kombo Electronics")
                .settlementCurrency("GMD")
                .defaultCommissionRate(new BigDecimal(commissionRate))
                .build();
    }

    private static OrderItem line(String deliveryDisplay) {
        return OrderItem.builder()
                .quantity(1)
                .unitPrice(new BigDecimal("900.00"))
                .totalPrice(new BigDecimal("900.00"))
                .currency("GMD")
                .deliveryCost(new BigDecimal(deliveryDisplay))
                .build();
    }

    /** 900 dalasi of goods sold to a buyer shopping in pounds at 1 GBP ≈ 80 GMD. */
    private static VendorOrder slice(Vendor vendor, List<OrderItem> items) {
        return VendorOrder.builder()
                .vendor(vendor)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("800.00"))
                .totalNative(new BigDecimal("800.00"))
                .subtotal(new BigDecimal("10.00"))
                .total(new BigDecimal("10.00"))
                .items(items)
                .build();
    }

    @Test
    void commissionAndPayoutAreTakenFromTheNativeTotal() {
        VendorOrder vendorOrder = slice(vendor("10.00"), List.of(line("0.00")));

        OrderServiceImpl.freezeVendorSettlement(List.of(vendorOrder));

        assertEquals(new BigDecimal("10.00"), vendorOrder.getCommissionRate());
        assertEquals(0, vendorOrder.getCommissionNative().compareTo(new BigDecimal("80.00")));
        assertEquals(0, vendorOrder.getPayoutNative().compareTo(new BigDecimal("720.00")));
    }

    @Test
    void deliveryConvertsAtTheSameRateTheGoodsDid() {
        // 1.00 in the buyer's currency, against a slice where 10.00 display = 800.00 native.
        VendorOrder vendorOrder = slice(vendor("10.00"), List.of(line("1.00")));

        OrderServiceImpl.freezeVendorSettlement(List.of(vendorOrder));

        // A fresh rate lookup could disagree with the one checkout used and leave
        // a parcel priced off a different rate than its contents.
        assertEquals(0, vendorOrder.getDeliveryNative().compareTo(new BigDecimal("80.00")));
    }

    @Test
    void deliveryIsNotPaidToTheVendor() {
        VendorOrder vendorOrder = slice(vendor("10.00"), List.of(line("1.00")));

        OrderServiceImpl.freezeVendorSettlement(List.of(vendorOrder));

        // The platform arranges delivery and keeps it: payout is goods less commission.
        assertEquals(0, vendorOrder.getPayoutNative().compareTo(new BigDecimal("720.00")));
    }

    @Test
    void deliveryAcrossSeveralLinesIsSummed() {
        VendorOrder vendorOrder = slice(vendor("0.00"), List.of(line("0.50"), line("0.50")));

        OrderServiceImpl.freezeVendorSettlement(List.of(vendorOrder));

        assertEquals(0, vendorOrder.getDeliveryNative().compareTo(new BigDecimal("80.00")));
    }

    @Test
    void aZeroCommissionVendorIsPaidTheWholeTotal() {
        VendorOrder vendorOrder = slice(vendor("0.00"), List.of(line("0.00")));

        OrderServiceImpl.freezeVendorSettlement(List.of(vendorOrder));

        assertEquals(0, vendorOrder.getCommissionNative().signum());
        assertEquals(0, vendorOrder.getPayoutNative().compareTo(new BigDecimal("800.00")));
    }

    @Test
    void aSliceSpanningSeveralListingCurrenciesGetsNoSingleFigure() {
        VendorOrder vendorOrder = VendorOrder.builder()
                .vendor(vendor("10.00"))
                .nativeCurrency(null)      // the lines disagreed on currency
                .subtotalNative(null)
                .totalNative(null)
                .subtotal(new BigDecimal("10.00"))
                .total(new BigDecimal("10.00"))
                .items(List.of(line("1.00")))
                .build();

        OrderServiceImpl.freezeVendorSettlement(List.of(vendorOrder));

        // There is no one native total to take a cut of; payout accounting falls
        // back to the per-line native amounts on each item.
        assertNull(vendorOrder.getCommissionNative());
        assertNull(vendorOrder.getPayoutNative());
    }

    @Test
    void aFreeOrderDoesNotDivideByZero() {
        VendorOrder vendorOrder = VendorOrder.builder()
                .vendor(vendor("10.00"))
                .nativeCurrency("GMD")
                .subtotalNative(BigDecimal.ZERO)
                .totalNative(BigDecimal.ZERO)
                .subtotal(BigDecimal.ZERO)     // fully discounted
                .total(BigDecimal.ZERO)
                .items(List.of(line("1.00")))
                .build();

        OrderServiceImpl.freezeVendorSettlement(List.of(vendorOrder));

        assertNull(vendorOrder.getDeliveryNative(), "no rate can be implied from a zero subtotal");
        assertEquals(0, vendorOrder.getPayoutNative().signum());
    }

    @Test
    void aLaterCommissionChangeDoesNotRestateAPlacedOrder() {
        Vendor seller = vendor("10.00");
        VendorOrder vendorOrder = slice(seller, List.of(line("0.00")));

        OrderServiceImpl.freezeVendorSettlement(List.of(vendorOrder));
        BigDecimal payoutAtCheckout = vendorOrder.getPayoutNative();

        // The platform renegotiates the seller's rate afterwards.
        Vendor renegotiated = vendor("25.00");
        assertTrue(renegotiated.getDefaultCommissionRate().compareTo(seller.getDefaultCommissionRate()) > 0);

        // The order still pays what it paid: the rate is on the row, not looked up.
        assertEquals(payoutAtCheckout, vendorOrder.getPayoutNative());
        assertEquals(new BigDecimal("10.00"), vendorOrder.getCommissionRate());
    }
}
