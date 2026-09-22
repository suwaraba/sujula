package com.sujula.dto.response.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.constant.VendorOrderStatus;

/** What an administrator is shown about orders and parcels in flight. */
public final class AdminDispatchResponses {

    private AdminDispatchResponses() {}

    // ── Orders ───────────────────────────────────────────────────────────────

    public record OrderRow(
            Long id, String orderNumber, OrderStatus status, String paymentStatus,
            String buyerName, String buyerEmail,
            /** Where the goods go, which is not where the buyer is (C1). */
            String destinationCity, String destinationCountry,
            BigDecimal total, String currency,
            int vendorOrders, List<String> storeNames,
            /** How long it has sat in its current state. What a stuck-order sweep reads. */
            long hoursInStatus,
            LocalDateTime placedAt) {}

    /**
     * One order in full: the slices, the money and the parcels.
     *
     * <p>Assembled in one response because the question an administrator is
     * answering — "what has actually happened to this order" — is never about
     * one of those three. Reading them from three screens is how somebody
     * decides on the first.
     */
    public record OrderDetail(
            OrderRow summary,
            List<VendorOrderView> slices,
            List<LedgerLine> ledger,
            List<ParcelView> parcels,
            List<String> warnings) {}

    public record VendorOrderView(
            Long id, String storeName, VendorOrderStatus status,
            BigDecimal total, String currency,
            BigDecimal totalNative, String nativeCurrency,
            BigDecimal fxRate, LocalDateTime fxRateAt,
            LocalDateTime acceptedAt, LocalDateTime readyAt, LocalDateTime cancelledAt,
            /** Set while a dispute is holding this slice's money still (C3). */
            LocalDateTime disputeFrozenAt,
            String rejectionReason) {}

    /** One row of what the seller was credited or charged, in their own currency. */
    public record LedgerLine(Long id, String type, BigDecimal amount, String currency,
                             String storeName, String description, String reference,
                             LocalDateTime availableFrom, LocalDateTime occurredAt) {}

    public record ParcelView(
            Long id, String reference, String trackingCode, ShipmentStatus status,
            String storeName, String destinationCity,
            int failedAttempts, LocalDateTime nextAttemptAfter,
            String heldAtPickupPoint, String shelfCode,
            List<LegView> legs,
            LocalDateTime collectedAt, LocalDateTime deliveredAt) {}

    public record LegView(Long id, int sequence, String legType, LegAssignmentStatus status,
                          String driverName, String driverPhone,
                          String from, String to,
                          LocalDateTime offeredAt, LocalDateTime offerExpiresAt,
                          LocalDateTime acceptedAt, LocalDateTime completedAt,
                          BigDecimal earning, String earningCurrency) {}

    public record OrderCancelled(Long orderId, OrderStatus status,
                                 List<Long> cancelledVendorOrderIds,
                                 List<String> refundReferences, int parcelsCancelled,
                                 String message) {}

    public record StatusForced(Long vendorOrderId, VendorOrderStatus from, VendorOrderStatus to,
                               String auditReference, String warning) {}

    public record OrderPlaced(Long orderId, String orderNumber, BigDecimal total, String currency,
                              List<Long> vendorOrderIds, String message) {}

    // ── The dispatch board ───────────────────────────────────────────────────

    public record ShipmentRow(
            Long id, String reference, ShipmentStatus status,
            String storeName, String destinationCity, String destinationCountry,
            String driverName,
            int failedAttempts,
            /** How long it has been sitting where it is. The board sorts on this. */
            long hoursWaiting,
            boolean overdue,
            LocalDateTime createdAt) {}

    /**
     * A parcel nobody is carrying, with who could.
     *
     * <p>The candidates are ranked and the ranking is explained. A dispatcher
     * who is handed a list with no reasons picks the first one, and the first
     * one is whoever the database happened to return.
     */
    public record UnassignedShipment(ShipmentRow parcel, List<DriverCandidate> candidates,
                                     String note) {}

    public record DriverCandidate(
            Long driverId, String name, String phone, String zone,
            Double distanceKm, BigDecimal acceptanceScore, int openJobs,
            boolean available, String vehicleType,
            /** Why this one is where it is in the list, in words. */
            String why) {}

    public record AssignmentMade(Long shipmentId, Long legId, Long driverId, String driverName,
                                 LocalDateTime offerExpiresAt, String message) {}

    public record AssignmentRemoved(Long shipmentId, Long legId, String previousDriver,
                                    boolean scoreAffected, String message) {}

    /**
     * A custody event recorded by hand.
     *
     * <p>Carries the warning it earns. An override is the one way a parcel
     * reaches DELIVERED without anybody presenting anything, and the response
     * says so rather than reading like an ordinary success.
     */
    public record HandoffOverridden(Long shipmentId, Long eventId, CustodyEventType type,
                                    ShipmentStatus statusNow, String note, String warning) {}

    public record ShipmentCancelled(Long shipmentId, ShipmentStatus status, String message) {}

    /** The whole chain, with the evidence attached to each link. */
    public record CustodyChainView(Long shipmentId, String reference, ShipmentStatus status,
                                   List<CustodyEventView> events, String note) {}

    public record CustodyEventView(
            Long id, CustodyEventType type,
            LocalDateTime occurredAt, LocalDateTime recordedAt,
            String recordedBy, String recordedByRole,
            boolean codePresented, String reasonCode,
            Double lat, Double lng, BigDecimal accuracyMetres,
            BigDecimal metresFromExpected, boolean withinGeofence,
            String photoUrl, String signatureUrl, String note,
            boolean capturedOffline,
            /** Set when this link was written by an administrator rather than earned. */
            boolean overridden) {}
}
