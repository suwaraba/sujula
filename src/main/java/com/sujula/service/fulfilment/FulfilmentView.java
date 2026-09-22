package com.sujula.service.fulfilment;

import org.springframework.stereotype.Component;

import com.sujula.dto.response.fulfilment.FulfilmentResponses;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.inventory.ImeiUnitRepository;

/**
 * The two questions a seller's order screen and the fulfilment endpoints must
 * answer the same way.
 *
 * <p><em>How much of the buyer do I show?</em> and <em>is this order packable
 * yet?</em> Both are asked twice — once when the detail page is drawn and once
 * when the seller presses the button — and two copies of either would eventually
 * disagree. The screen would then offer a button the service refuses, or show a
 * field the service would not have released.
 *
 * <p>Kept deliberately small and read-only. Nothing here writes.
 */
@Component
public class FulfilmentView {

    private final ImeiUnitRepository imeiUnits;
    private final PickupPointRepository pickupPoints;

    public FulfilmentView(ImeiUnitRepository imeiUnits, PickupPointRepository pickupPoints) {
        this.imeiUnits = imeiUnits;
        this.pickupPoints = pickupPoints;
    }

    /**
     * Where the parcel goes, and nothing more.
     *
     * <p>Built from the order's <em>delivery</em> snapshot. The payer's country,
     * currency and identity sit on the same row and none of them are read here:
     * that separation is C1, and a seller who could see both sides would be a
     * seller who can tell which of their buyers is sending money home.
     */
    public FulfilmentResponses.Shipping shippingFor(VendorOrder slice) {
        Order order = slice == null ? null : slice.getOrder();
        if (order == null) {
            return null;
        }
        String pickupName = null;
        if (order.getPickupPointId() != null) {
            pickupName = pickupPoints.findById(order.getPickupPointId())
                    .map(point -> point.getName())
                    .orElse(null);
        }
        String deliveryCountry = order.getShippingCountry();
        String vendorCountry = slice.getVendor() == null ? null : slice.getVendor().getPickupCountryCode();
        boolean international = deliveryCountry != null && vendorCountry != null
                && !deliveryCountry.equalsIgnoreCase(vendorCountry);

        return new FulfilmentResponses.Shipping(
                order.getShippingFullName(),
                order.getShippingCity(),
                deliveryCountry,
                international,
                order.getDeliveryMode() == null ? null : order.getDeliveryMode().name(),
                pickupName,
                phoneHint(order.getShippingPhone()));
    }

    /**
     * How many handsets a line needs bound before it can be packed.
     *
     * <p>Zero for everything not tracked handset by handset, which is most of
     * the catalogue. Serialisation is a property of the variant — it has IMEI
     * units behind it — rather than a flag somebody sets, so it cannot be
     * switched off to get past the check.
     */
    public int requiredHandsets(OrderItem item) {
        if (item == null || item.getVariant() == null || item.getVariant().getId() == null) {
            return 0;
        }
        if (!imeiUnits.existsByVariantId(item.getVariant().getId())) {
            return 0;
        }
        return item.getQuantity() == null ? 0 : item.getQuantity();
    }

    /** How many of this line's handsets are still to scan. */
    public int outstandingHandsets(OrderItem item) {
        int required = requiredHandsets(item);
        return required <= 0 ? 0 : Math.max(0, required - item.assignedImeiList().size());
    }

    /** Whether every serialised line on the slice has all of its handsets. */
    public boolean everyLineBound(VendorOrder slice) {
        for (OrderItem item : slice.getItems()) {
            if (outstandingHandsets(item) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The last three digits of the recipient's number, and no more.
     *
     * <p>Enough for a seller to confirm they have the right parcel in front of
     * them; not enough to ring the recipient and route the sale off the
     * platform, which is why the full number stays with the driver.
     */
    private static String phoneHint(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() < 3 ? null : "••• " + digits.substring(digits.length() - 3);
    }
}
