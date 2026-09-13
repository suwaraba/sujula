package com.sujula.service.delivery;

import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DeliveryScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Delivery price is based on distance and weight" — asserted rather than
 * assumed.
 *
 * <p>These are the properties a rate card has to have for the figure on a cart
 * to be defensible. A price that did not move with distance would mean a parcel
 * crossing the country costs what one crossing a street costs, and the platform
 * absorbs the difference on every long leg until somebody notices.
 */
class DeliveryPriceShapeTest {

    private DeliveryPricingProperties rates;

    @BeforeEach
    void setUp() {
        rates = new DeliveryPricingProperties();
    }

    private BigDecimal price(String km, String kg) {
        return rates.priceLeg(new BigDecimal(km), new BigDecimal(kg),
                DeliveryScope.REGIIONAL, DeliveryMode.HOME_DELIVERY);
    }

    // ── Distance ─────────────────────────────────────────────────────────────

    @Test
    void aFurtherParcelCostsMore() {
        assertTrue(price("50", "1").compareTo(price("5", "1")) > 0,
                "a leg across the country cannot cost what one across a street costs");
    }

    @Test
    void priceRisesMonotonicallyWithDistance() {
        BigDecimal previous = price("0", "1");
        for (int km = 5; km <= 200; km += 5) {
            BigDecimal current = price(String.valueOf(km), "1");
            assertTrue(current.compareTo(previous) >= 0,
                    "price fell between " + (km - 5) + "km and " + km + "km");
            previous = current;
        }
    }

    /**
     * The first few kilometres are included. A rider is dispatched at all, which
     * costs the same whether the address is round the corner or across town.
     */
    @Test
    void shortLegsInsideTheIncludedDistanceCostTheSame() {
        assertEquals(0, price("0", "0.5").compareTo(price("2", "0.5")));
        assertEquals(0, price("1", "0.5").compareTo(rates.getMinFee()));
    }

    // ── Weight ───────────────────────────────────────────────────────────────

    @Test
    void aHeavierParcelCostsMore() {
        assertTrue(price("20", "25").compareTo(price("20", "0.5")) > 0);
    }

    @Test
    void priceRisesMonotonicallyWithWeight() {
        BigDecimal previous = price("20", "0");
        for (int kg = 1; kg <= 40; kg++) {
            BigDecimal current = price("20", String.valueOf(kg));
            assertTrue(current.compareTo(previous) >= 0,
                    "price fell between " + (kg - 1) + "kg and " + kg + "kg");
            previous = current;
        }
    }

    /** Distance and weight move the price independently — neither is a proxy. */
    @Test
    void distanceAndWeightAreSeparateInputs() {
        BigDecimal nearLight = price("5", "0.5");
        BigDecimal farLight = price("100", "0.5");
        BigDecimal nearHeavy = price("5", "30");

        assertTrue(farLight.compareTo(nearLight) > 0, "distance alone must move it");
        assertTrue(nearHeavy.compareTo(nearLight) > 0, "weight alone must move it");
    }

    // ── Floors, ceilings and scope ───────────────────────────────────────────

    @Test
    void nothingIsDeliveredBelowTheFloor() {
        assertTrue(price("0", "0").compareTo(rates.getMinFee()) >= 0);
    }

    @Test
    void aCeilingCapsTheLongestLegsWhenOneIsSet() {
        rates.setMaxFee(new BigDecimal("500"));

        assertEquals(0, new BigDecimal("500.00").compareTo(price("5000", "50")));
    }

    /**
     * Scope scales the whole leg. A parcel crossing a border is not merely
     * further — it is handled differently, and the multiplier is where that is
     * expressed.
     */
    @Test
    void scopeScalesThePrice() {
        BigDecimal regional = rates.priceLeg(new BigDecimal("50"), BigDecimal.ONE,
                DeliveryScope.REGIIONAL, DeliveryMode.HOME_DELIVERY);
        BigDecimal national = rates.priceLeg(new BigDecimal("50"), BigDecimal.ONE,
                DeliveryScope.NATIONAL, DeliveryMode.HOME_DELIVERY);
        BigDecimal global = rates.priceLeg(new BigDecimal("50"), BigDecimal.ONE,
                DeliveryScope.GLOBAL, DeliveryMode.HOME_DELIVERY);

        assertTrue(national.compareTo(regional) > 0);
        assertTrue(global.compareTo(national) > 0);
    }

    /** A hub run is a scheduled leg; collecting from the shop is no leg at all. */
    @Test
    void howTheGoodsAreReceivedScalesItToo() {
        BigDecimal door = rates.priceLeg(new BigDecimal("20"), BigDecimal.ONE,
                DeliveryScope.REGIIONAL, DeliveryMode.HOME_DELIVERY);
        BigDecimal hub = rates.priceLeg(new BigDecimal("20"), BigDecimal.ONE,
                DeliveryScope.REGIIONAL, DeliveryMode.PICKUP_POINT);
        BigDecimal shop = rates.priceLeg(new BigDecimal("20"), BigDecimal.ONE,
                DeliveryScope.REGIIONAL, DeliveryMode.VENDOR_PICKUP);

        assertTrue(hub.compareTo(door) < 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(shop), "nothing is delivered, so nothing is charged");
    }

    @Test
    void aNullDistanceOrWeightIsTreatedAsZeroRatherThanFailing() {
        assertEquals(0, rates.priceLeg(null, null, DeliveryScope.REGIIONAL,
                DeliveryMode.HOME_DELIVERY).compareTo(rates.getMinFee()));
    }
}
