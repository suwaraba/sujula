package com.sujula.service.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.Vendor;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;

/**
 * The Order-first boundary joining commercial fulfilment to physical custody.
 *
 * <p>Every method that creates, collects or stops a production parcel follows
 * Order -&gt; VendorOrder -&gt; Shipment -&gt; ShipmentLeg -&gt; HandoverCode. Keeping
 * that sequence in one component is what makes ready, collection and
 * cancellation serialize rather than leave a cancelled order beside a newly
 * collected parcel.
 */
@Component
public class HomeShipmentCoordinator {

    private final OrderRepository orders;
    private final VendorOrderRepository vendorOrders;
    private final ShipmentRepository shipments;
    private final ShipmentLegRepository legs;
    private final HandoverCodeRepository codes;
    private final CustodyEventRepository events;
    private final CustodyChain chain;

    public HomeShipmentCoordinator(OrderRepository orders, VendorOrderRepository vendorOrders,
                                   ShipmentRepository shipments, ShipmentLegRepository legs,
                                   HandoverCodeRepository codes, CustodyEventRepository events,
                                   CustodyChain chain) {
        this.orders = orders;
        this.vendorOrders = vendorOrders;
        this.shipments = shipments;
        this.legs = legs;
        this.codes = codes;
        this.events = events;
        this.chain = chain;
    }

    /** Scope a vendor first, then acquire the common parent-first write locks. */
    @Transactional
    public VendorOrder lockOwnedSlice(Long vendorOrderId, Long vendorId) {
        VendorOrderRepository.OwnedCommercialParent scoped = vendorOrders
                .findOwnedCommercialParent(vendorOrderId, vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorOrder", vendorOrderId));
        Long orderId = scoped.getOrderId();
        lockOrder(orderId);
        VendorOrder locked = lockSlice(orderId, vendorOrderId);
        if (locked.getVendor() == null || !vendorId.equals(locked.getVendor().getId())) {
            throw new ResourceNotFoundException("VendorOrder", vendorOrderId);
        }
        return locked;
    }

    /** Reload and lock one slice after its parent Order is already locked. */
    @Transactional
    public VendorOrder lockSlice(Long orderId, Long vendorOrderId) {
        return vendorOrders.findByIdAndOrderIdForUpdate(vendorOrderId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorOrder", vendorOrderId));
    }

    /**
     * Lock the commercial parents before the Shipment used by a collection.
     * The caller must perform driver scoping before invoking this method.
     */
    @Transactional
    public CollectionContext lockForCollection(Long shipmentId) {
        ShipmentRepository.CommercialParent parent = shipments.findCommercialParent(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        Order order = lockOrder(parent.getOrderId());
        VendorOrder slice = lockSlice(order.getId(), parent.getVendorOrderId());
        Shipment shipment = shipments.lockForCustody(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        if (shipment.getVendorOrder() == null
                || !slice.getId().equals(shipment.getVendorOrder().getId())) {
            throw new IllegalStateException("Shipment commercial parent changed while locking");
        }
        return new CollectionContext(order, slice, shipment);
    }

    /** Create or validate the one direct-to-recipient production route. */
    @Transactional
    public Shipment provisionHome(VendorOrder slice) {
        Order order = requireReadyOrder(slice);
        Vendor vendor = slice.getVendor();
        requirePhysicalSnapshot(order, vendor);

        Shipment shipment = shipments.lockByVendorOrderId(slice.getId()).orElse(null);
        if (shipment == null) {
            shipment = shipments.save(Shipment.builder()
                    .reference(newShipmentReference())
                    .vendorOrder(slice)
                    .recipientName(order.getShippingFullName().trim())
                    .recipientPhone(order.getShippingPhone().trim())
                    .destinationStreet(destinationStreet(order))
                    .destinationCity(order.getShippingCity().trim())
                    .destinationCountry(upper(order.getShippingCountry()))
                    .destinationLatitude(order.getShippingLatitude())
                    .destinationLongitude(order.getShippingLongitude())
                    .originLatitude(vendor.getPickupLatitude())
                    .originLongitude(vendor.getPickupLongitude())
                    .originAddress(originAddress(vendor))
                    .parcelCount(parcelCount(slice))
                    .deliveryFee(deliveryFee(slice))
                    .feeCurrency(order.getCurrency())
                    .build());
        } else if (shipment.getCancelledAt() != null || shipment.getCollectedAt() != null
                || (shipment.getStatus() != null && shipment.getStatus().isFinished())) {
            throw new BadRequestException(
                    "This parcel has already entered or finished custody and cannot be "
                            + "reprovisioned at vendor ready.");
        }

        List<ShipmentLeg> existing = legs.lockByShipmentIdOrderBySequenceAsc(shipment.getId());
        if (existing.isEmpty()) {
            legs.save(ShipmentLeg.builder()
                    .shipment(shipment)
                    .sequence(1)
                    .legType(LegType.ORIGIN_TO_RECIPIENT)
                    .assignmentStatus(LegAssignmentStatus.UNASSIGNED)
                    .originLatitude(shipment.getOriginLatitude())
                    .originLongitude(shipment.getOriginLongitude())
                    .originLabel(shipment.getOriginAddress())
                    .destinationLatitude(shipment.getDestinationLatitude())
                    .destinationLongitude(shipment.getDestinationLongitude())
                    .destinationLabel(destinationLabel(order))
                    .build());
        } else {
            requireCompatibleDirectRoute(shipment, existing);
        }
        return shipment;
    }

    /** Stop a linked parcel only while nothing has physically left the vendor. */
    @Transactional
    public boolean cancelBeforeCollection(VendorOrder slice) {
        Shipment shipment = shipments.lockByVendorOrderId(slice.getId()).orElse(null);
        if (shipment == null) {
            return false;
        }
        return cancelLockedBeforeCollection(shipment);
    }

    /** Order-first form used by the administrative Shipment cancellation surface. */
    @Transactional
    public Shipment cancelShipmentBeforeCollection(Long shipmentId) {
        ShipmentRepository.CommercialParent parent = shipments.findCommercialParent(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        lockOrder(parent.getOrderId());
        lockSlice(parent.getOrderId(), parent.getVendorOrderId());
        Shipment shipment = shipments.lockForCustody(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        cancelLockedBeforeCollection(shipment);
        return shipment;
    }

    private boolean cancelLockedBeforeCollection(Shipment shipment) {
        if (shipment.getCollectedAt() != null
                || events.existsByShipmentIdAndType(shipment.getId(), CustodyEventType.COLLECTED)) {
            throw new BadRequestException(
                    "This parcel has already been collected. It cannot use pre-handover "
                            + "cancellation; use the return/refund workflow instead.");
        }
        if (shipment.getCancelledAt() != null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        shipment.setCancelledAt(now);
        chain.rederive(shipment,
                events.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId()));

        for (ShipmentLeg leg : legs.lockByShipmentIdOrderBySequenceAsc(shipment.getId())) {
            if (leg.getAssignmentStatus() != LegAssignmentStatus.COMPLETED
                    && leg.getAssignmentStatus() != LegAssignmentStatus.CANCELLED) {
                leg.setAssignmentStatus(LegAssignmentStatus.CANCELLED);
                leg.setOfferExpiresAt(null);
                legs.save(leg);
            }
        }

        invalidate(codes.lockLiveReleaseCodes(shipment.getVendorOrder().getId(), now), now);
        invalidate(codes.lockAllLiveForShipment(shipment.getId(), now), now);
        return true;
    }

    private void invalidate(List<HandoverCode> live, LocalDateTime now) {
        for (HandoverCode code : live) {
            code.setInvalidatedAt(now);
            codes.save(code);
        }
    }

    private Order lockOrder(Long orderId) {
        return orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    private static Order requireReadyOrder(VendorOrder slice) {
        Order order = slice.getOrder();
        if (order == null || order.getId() == null) {
            throw new BadRequestException("This seller order has no parent order to deliver.");
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw new BadRequestException(
                    "This order is not paid, so a parcel cannot be released for delivery.");
        }
        if (order.getStatus() != OrderStatus.CONFIRMED
                && order.getStatus() != OrderStatus.PROCESSING
                && order.getStatus() != OrderStatus.SHIPPED) {
            throw new BadRequestException(
                    "This order is " + order.getStatus()
                            + ", so it cannot enter delivery fulfilment.");
        }
        if (order.getDeliveryMode() != DeliveryMode.HOME_DELIVERY) {
            throw new BadRequestException(
                    "Pickup-point fulfilment is not active yet. This order cannot be marked ready "
                            + "until its pickup route and handover codes are supported.");
        }
        return order;
    }

    private static void requirePhysicalSnapshot(Order order, Vendor vendor) {
        if (vendor == null
                || blank(vendor.getPickupStreet()) || blank(vendor.getPickupCity())
                || blank(vendor.getPickupCountryCode())
                || vendor.getPickupLatitude() == null || vendor.getPickupLongitude() == null) {
            throw new BadRequestException(
                    "Add the vendor pickup address and map location before marking this parcel ready.");
        }
        if (blank(order.getShippingFullName()) || blank(order.getShippingPhone())
                || blank(order.getShippingStreet()) || blank(order.getShippingCity())
                || blank(order.getShippingCountry())
                || order.getShippingLatitude() == null || order.getShippingLongitude() == null) {
            throw new BadRequestException(
                    "This order has no complete recipient address, phone and map location, so it "
                            + "cannot be dispatched safely.");
        }
    }

    private static void requireCompatibleDirectRoute(Shipment shipment, List<ShipmentLeg> existing) {
        if (existing.size() != 1) {
            throw new BadRequestException(
                    "This parcel already has a non-home route. It cannot be reprovisioned at ready.");
        }
        ShipmentLeg leg = existing.get(0);
        if (!Integer.valueOf(1).equals(leg.getSequence())
                || leg.getLegType() != LegType.ORIGIN_TO_RECIPIENT
                || leg.getOriginPickupPoint() != null || leg.getDestinationPickupPoint() != null) {
            throw new BadRequestException(
                    "This parcel's existing route is not the direct home-delivery route.");
        }
    }

    private String newShipmentReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = "SHP-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
            if (!shipments.existsByReference(reference)) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not allocate a shipment reference");
    }

    private static int parcelCount(VendorOrder slice) {
        int count = slice.getItems() == null ? 0 : slice.getItems().stream()
                .map(OrderItem::getQuantity)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        return Math.max(1, count);
    }

    private static BigDecimal deliveryFee(VendorOrder slice) {
        if (slice.getItems() == null) {
            return BigDecimal.ZERO;
        }
        return slice.getItems().stream()
                .map(OrderItem::getDeliveryCost)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String originAddress(Vendor vendor) {
        return join(vendor.getPickupStreet(), vendor.getPickupCity(),
                vendor.getPickupState(), vendor.getPickupPostalCode(),
                upper(vendor.getPickupCountryCode()));
    }

    private static String destinationStreet(Order order) {
        return join(order.getShippingStreet(), order.getShippingApartment());
    }

    private static String destinationLabel(Order order) {
        return join(order.getShippingStreet(), order.getShippingApartment(),
                order.getShippingCity(), order.getShippingState(),
                order.getShippingPostalCode(), upper(order.getShippingCountry()));
    }

    private static String join(String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(part -> !blank(part))
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CollectionContext(Order order, VendorOrder slice, Shipment shipment) {}
}
