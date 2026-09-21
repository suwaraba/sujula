package com.sujula.config.seed;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.DeliveryStatus;
import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.LegType;
import com.sujula.model.constant.RecipientInstructionType;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.model.delivery.Delivery;
import com.sujula.model.delivery.DeliveryTracking;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.delivery.ProofOfDelivery;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.ParcelAccessCode;
import com.sujula.model.shipment.RecipientInstruction;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;

/**
 * Parcels, the legs they travel on, and the evidence at every handover.
 *
 * <p>This is where C4 lives. Nothing here reaches {@code DELIVERED} because a
 * field was set: each shipment's status is the consequence of the custody events
 * underneath it, and every one of those events carries what was actually
 * presented — a six-digit code read back, a photograph, a signature, a position
 * and how far that position was from where the parcel was supposed to be. The
 * failed attempt and the returned parcel are here for the same reason: they are
 * the rows that show the chain can break, and a chain that never breaks in the
 * sample data is one nobody has tested breaking.
 *
 * <p>C5 is here too. The recipient codes go to a phone number, and the people
 * reading them back — Aji Ceesay in Serrekunda, Mariama Jallow in Brikama —
 * have no accounts on this platform and never will.
 *
 * <p>Twelve shipments, one per vendor order, between them holding every
 * {@code ShipmentStatus}, every {@code LegType} and every
 * {@code LegAssignmentStatus} the model defines.
 */
@Component
class FulfilmentStage implements SeedStage {

    @Override
    public String name() {
        return "Shipments, legs, custody events and deliveries";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        shipments(cat);
        cat.flush();
        legs(cat);
        cat.flush();
        custodyEvents(cat);
        Map<String, ParcelAccessCode> codes = parcelAccessCodes(cat);
        // Flushed before the instructions, because each instruction names the
        // id of the code that proved it and an unflushed row has no id yet.
        cat.flush();
        recipientInstructions(cat, codes);
        deliveries(cat);
        cat.flush();
        handoverCodes(cat);
        deliveryTracking(cat);
        proofOfDelivery(cat);
    }

    // ── Shipments ────────────────────────────────────────────────────────────

    private void shipments(SeedCatalogue cat) {
        // Delivered, on the evidence of a code presented at the door.
        Shipment s1001 = shipment(cat, "shp-1001", "SHP-000001", "TRKSHP0000001001",
                "1001-bp", ShipmentStatus.DELIVERED,
                "Aji Ceesay", "+2207700100",
                "Sayerr Jobe Avenue, near Westfield", "Serrekunda", "GM", 13.4383, -16.6781,
                13.4530, -16.5775, "Banjul Phones, 41 Liberation Avenue",
                1, "210.00", "GMD");
        s1001.applyDerivedState(ShipmentStatus.DELIVERED, 0,
                cat.daysAgo(10), cat.daysAgo(9), null);

        Shipment s1002 = shipment(cat, "shp-1002", "SHP-000002", "TRKSHP0000001002",
                "1002-bp", ShipmentStatus.OUT_FOR_DELIVERY,
                "Aji Ceesay", "+2207700100",
                "Sayerr Jobe Avenue, near Westfield", "Serrekunda", "GM", 13.4383, -16.6781,
                13.4530, -16.5775, "Banjul Phones, 41 Liberation Avenue",
                1, "210.00", "GMD");
        s1002.applyDerivedState(ShipmentStatus.OUT_FOR_DELIVERY, 0, cat.daysAgo(2), null, null);

        // A driver is assigned and the seller has not finished packing. Two
        // independent facts, and neither implies the other.
        shipment(cat, "shp-1003", "SHP-000003", "TRKSHP0000001003",
                "1002-dt", ShipmentStatus.DRIVER_ASSIGNED,
                "Aji Ceesay", "+2207700100",
                "Sayerr Jobe Avenue, near Westfield", "Serrekunda", "GM", 13.4383, -16.6781,
                14.6690, -17.4370, "Dakar Tech, 14 Rue Mohamed V",
                1, "1500", "XOF");

        // Waiting on a shelf at Brikama. The recipient has a code and no
        // account (C5).
        Shipment s1004 = shipment(cat, "shp-1004", "SHP-000004", "TRKSHP0000001004",
                "1003-bp", ShipmentStatus.AT_PICKUP_POINT,
                "Mariama Jallow", "+2207700200",
                "Brikama Nyambai Road, opposite the mosque", "Brikama", "GM", 13.2712, -16.6494,
                13.4530, -16.5775, "Banjul Phones, 41 Liberation Avenue",
                1, "150.00", "GMD");
        s1004.setHeldAtPickupPoint(cat.pickupPoint("brikama"));
        s1004.setShelfCode("B-14");
        s1004.setStoredAt(cat.daysAgo(3));
        s1004.setStorageDeadline(cat.daysAhead(2));
        s1004.setPickupCommission(SeedCatalogue.money("30.00"));
        s1004.setPickupCommissionCurrency("GMD");
        s1004.applyDerivedState(ShipmentStatus.AT_PICKUP_POINT, 0, cat.daysAgo(4), null, null);

        // One attempt failed. Nobody was in; the next is tomorrow morning.
        Shipment s1005 = shipment(cat, "shp-1005", "SHP-000005", "TRKSHP0000001005",
                "1004-sh", ShipmentStatus.ATTEMPT_FAILED,
                "Binta Touray", "+2207200111",
                "12 Rene Blain Street", "Banjul", "GM", 13.4549, -16.5790,
                13.4390, -16.6790, "Serrekunda Home, Sayerr Jobe Avenue",
                1, "150.00", "GMD");
        s1005.setNextAttemptAfter(cat.hoursAhead(14));
        s1005.applyDerivedState(ShipmentStatus.ATTEMPT_FAILED, 1, cat.hoursAgo(26), null, null);

        Shipment s1006 = shipment(cat, "shp-1006", "SHP-000006", "TRKSHP0000001006",
                "1005-sh", ShipmentStatus.CANCELLED,
                "Binta Touray", "+2207200111",
                "12 Rene Blain Street", "Banjul", "GM", 13.4549, -16.5790,
                13.4390, -16.6790, "Serrekunda Home, Sayerr Jobe Avenue",
                1, "220.00", "GMD");
        s1006.setCancelledAt(cat.daysAgo(2).plusHours(6));

        Shipment s1007 = shipment(cat, "shp-1007", "SHP-000007", "TRKSHP0000001007",
                "1006-dt", ShipmentStatus.DELIVERED,
                "Cheikh Ndiaye", "+221770333444",
                "Avenue Cheikh Anta Diop, Point E", "Dakar", "SN", 14.6937, -17.4441,
                14.6690, -17.4370, "Dakar Tech, 14 Rue Mohamed V",
                2, "1500", "XOF");
        s1007.applyDerivedState(ShipmentStatus.DELIVERED, 0,
                cat.daysAgo(17).plusHours(4), cat.daysAgo(16), null);

        // Three attempts, nobody there, and back to the seller. The end of a
        // custody chain that did not complete.
        Shipment s1008 = shipment(cat, "shp-1008", "SHP-000008", "TRKSHP0000001008",
                "1007-ks", ShipmentStatus.RETURNED,
                "Fatoumata Mendy", "+2207700300",
                "Kololi, near the craft market", "Kololi", "GM", 13.4470, -16.6950,
                13.4470, -16.6950, "Kololi Style, Senegambia Strip",
                1, "150.00", "GMD");
        s1008.applyDerivedState(ShipmentStatus.RETURNED, 3,
                cat.daysAgo(23), null, cat.daysAgo(19));

        // The driver is standing in the shop. Nothing has changed hands yet, and
        // the status says exactly that.
        Shipment s1009 = shipment(cat, "shp-1009", "SHP-000009", "TRKSHP0000001009",
                "1008-bp", ShipmentStatus.AT_ORIGIN,
                "Ousainou Bojang", "+2207700400",
                "Latrikunda German, behind the school", "Serrekunda", "GM", 13.4330, -16.6690,
                13.4530, -16.5775, "Banjul Phones, 41 Liberation Avenue",
                1, "150.00", "GMD");
        s1009.applyDerivedState(ShipmentStatus.AT_ORIGIN, 0, null, null, null);

        // Offered to a driver and not yet accepted.
        shipment(cat, "shp-1010", "SHP-000010", "TRKSHP0000001010",
                "1009-bp", ShipmentStatus.DRIVER_OFFERED,
                "Mariama Jallow", "+2207700200",
                "Brikama Nyambai Road, opposite the mosque", "Brikama", "GM", 13.2712, -16.6494,
                13.4530, -16.5775, "Banjul Phones, 41 Liberation Avenue",
                1, "150.00", "GMD");

        // Nobody has been offered it yet. The oldest thing in the queue.
        shipment(cat, "shp-1011", "SHP-000011", "TRKSHP0000001011",
                "1010-bp", ShipmentStatus.AWAITING_COLLECTION,
                "Yankuba Bah", "+2207200222",
                "Atlantic Road, Bakau New Town", "Bakau", "GM", 13.4780, -16.6810,
                13.4530, -16.5775, "Banjul Phones, 41 Liberation Avenue",
                1, "120.00", "GMD");

        // On the road to a pickup point, for a recipient with no smartphone.
        Shipment s1012 = shipment(cat, "shp-1012", "SHP-000012", "TRKSHP0000001012",
                "1011-sh", ShipmentStatus.IN_TRANSIT,
                "Kaddy Secka", "+2207700500",
                "Collect from Westfield Corner Pharmacy", "Serrekunda", "GM", 13.4405, -16.6775,
                13.4390, -16.6790, "Serrekunda Home, Sayerr Jobe Avenue",
                1, "150.00", "GMD");
        s1012.setPickupCommission(SeedCatalogue.money("35.00"));
        s1012.setPickupCommissionCurrency("GMD");
        s1012.applyDerivedState(ShipmentStatus.IN_TRANSIT, 0, cat.hoursAgo(4), null, null);
    }

    private Shipment shipment(SeedCatalogue cat, String key, String reference, String trackingCode,
                              String vendorOrderKey, ShipmentStatus status,
                              String recipientName, String recipientPhone,
                              String street, String city, String country,
                              Double destLat, Double destLng,
                              Double originLat, Double originLng, String originAddress,
                              int parcels, String fee, String feeCurrency) {
        VendorOrder vendorOrder = cat.vendorOrder(vendorOrderKey);
        Shipment shipment = Shipment.builder()
                .reference(reference).trackingCode(trackingCode)
                .vendorOrder(vendorOrder).status(status)
                .recipientName(recipientName).recipientPhone(recipientPhone)
                .destinationStreet(street).destinationCity(city).destinationCountry(country)
                .destinationLatitude(destLat).destinationLongitude(destLng)
                .originLatitude(originLat).originLongitude(originLng).originAddress(originAddress)
                .parcelCount(parcels)
                .deliveryFee(new BigDecimal(fee)).feeCurrency(feeCurrency)
                .build();
        cat.shipments.put(key, cat.save(shipment));
        return shipment;
    }

    // ── Legs ─────────────────────────────────────────────────────────────────

    /**
     * The legs, covering every assignment status a leg can hold.
     *
     * <p>A declined offer and an expired one both leave the leg unworked, and
     * they are not the same thing: one is a driver's decision, worth counting
     * against their acceptance score, and the other is nobody's. Both are here.
     */
    private void legs(SeedCatalogue cat) {
        leg(cat, "shp-1001", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.COMPLETED,
                "ebrima", cat.daysAgo(11), cat.daysAgo(11).plusMinutes(4), null,
                cat.daysAgo(10), cat.daysAgo(9),
                13.4530, -16.5775, "Banjul Phones",
                13.4383, -16.6781, "Sayerr Jobe Avenue, Serrekunda",
                "9.400", "210.00", "GMD", null);

        leg(cat, "shp-1002", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.IN_PROGRESS,
                "ebrima", cat.daysAgo(3), cat.daysAgo(3).plusMinutes(2), null,
                cat.daysAgo(2), null,
                13.4530, -16.5775, "Banjul Phones",
                13.4383, -16.6781, "Sayerr Jobe Avenue, Serrekunda",
                "9.400", "210.00", "GMD", null);

        leg(cat, "shp-1003", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.ACCEPTED,
                "aminata", cat.daysAgo(3), cat.daysAgo(3).plusMinutes(9), null,
                null, null,
                14.6690, -17.4370, "Dakar Tech",
                13.4383, -16.6781, "Sayerr Jobe Avenue, Serrekunda",
                "214.600", "12000", "XOF", null);

        leg(cat, "shp-1004", 1, LegType.ORIGIN_TO_PICKUP, LegAssignmentStatus.COMPLETED,
                "saikou", cat.daysAgo(4), cat.daysAgo(4).plusMinutes(6), null,
                cat.daysAgo(4).plusHours(3), cat.daysAgo(3),
                13.4530, -16.5775, "Banjul Phones",
                13.2710, -16.6490, "Brikama Market Stationers",
                "34.200", "480.00", "GMD", null);
        // The last hop is over the counter, and nobody is assigned to it because
        // nobody carries it: the recipient walks in.
        leg(cat, "shp-1004", 2, LegType.PICKUP_TO_COUNTER, LegAssignmentStatus.UNASSIGNED,
                null, null, null, null, null, null,
                13.2710, -16.6490, "Brikama Market Stationers",
                13.2710, -16.6490, "Counter",
                "0.000", "0.00", "GMD", null);

        leg(cat, "shp-1005", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.IN_PROGRESS,
                "ebrima", cat.hoursAgo(28), cat.hoursAgo(27), null,
                cat.hoursAgo(26), null,
                13.4390, -16.6790, "Serrekunda Home",
                13.4549, -16.5790, "12 Rene Blain Street, Banjul",
                "11.700", "150.00", "GMD", null);

        leg(cat, "shp-1006", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.CANCELLED,
                null, null, null, null, null, null,
                13.4390, -16.6790, "Serrekunda Home",
                13.4549, -16.5790, "12 Rene Blain Street, Banjul",
                "11.700", "220.00", "GMD", "Order cancelled by the seller before collection.");

        leg(cat, "shp-1007", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.COMPLETED,
                "aminata", cat.daysAgo(17), cat.daysAgo(17).plusMinutes(3), null,
                cat.daysAgo(17).plusHours(4), cat.daysAgo(16),
                14.6690, -17.4370, "Dakar Tech",
                14.6937, -17.4441, "Avenue Cheikh Anta Diop, Point E",
                "4.100", "1500", "XOF", null);

        leg(cat, "shp-1008", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.COMPLETED,
                "saikou", cat.daysAgo(24), cat.daysAgo(24).plusMinutes(11), null,
                cat.daysAgo(23), cat.daysAgo(20),
                13.4470, -16.6950, "Kololi Style",
                13.4470, -16.6950, "Kololi, near the craft market",
                "1.200", "150.00", "GMD", null);
        // The parcel went back through a counter rather than straight to the
        // seller, because the seller's shop was shut.
        leg(cat, "shp-1008", 2, LegType.PICKUP_TO_PICKUP, LegAssignmentStatus.COMPLETED,
                "saikou", cat.daysAgo(20), cat.daysAgo(20).plusMinutes(5), null,
                cat.daysAgo(20).plusHours(1), cat.daysAgo(19),
                13.4405, -16.6775, "Westfield Corner Pharmacy",
                13.4470, -16.6950, "Kololi Style",
                "3.300", "90.00", "GMD", null);

        leg(cat, "shp-1009", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.ACCEPTED,
                "jainaba", cat.hoursAgo(2), cat.hoursAgo(2).plusMinutes(1), null,
                null, null,
                13.4530, -16.5775, "Banjul Phones",
                13.4330, -16.6690, "Latrikunda German",
                "10.800", "150.00", "GMD", null);

        // Declined, then offered to somebody else. The offer that is still open
        // has an expiry on it; the one that was declined has a reason.
        leg(cat, "shp-1010", 1, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.DECLINED,
                "ebrima", cat.hoursAgo(5), null, cat.hoursAgo(5).plusMinutes(2),
                null, null,
                13.4530, -16.5775, "Banjul Phones",
                13.2712, -16.6494, "Brikama Nyambai Road",
                "34.900", "480.00", "GMD", "Already carrying four parcels the other way.");
        ShipmentLeg openOffer = leg(cat, "shp-1010", 2, LegType.ORIGIN_TO_RECIPIENT,
                LegAssignmentStatus.OFFERED,
                "saikou", cat.hoursAgo(1), null, null, null, null,
                13.4530, -16.5775, "Banjul Phones",
                13.2712, -16.6494, "Brikama Nyambai Road",
                "34.900", "480.00", "GMD", null);
        openOffer.setOfferExpiresAt(cat.hoursAhead(1));

        // Offered and nobody answered. Different from declined: no driver made
        // a decision, so nothing should count against anybody.
        ShipmentLeg lapsed = leg(cat, "shp-1011", 1, LegType.ORIGIN_TO_RECIPIENT,
                LegAssignmentStatus.EXPIRED,
                "bakary", cat.hoursAgo(9), null, null, null, null,
                13.4530, -16.5775, "Banjul Phones",
                13.4780, -16.6810, "Atlantic Road, Bakau New Town",
                "12.100", "180.00", "GMD", null);
        lapsed.setOfferExpiresAt(cat.hoursAgo(8));
        leg(cat, "shp-1011", 2, LegType.ORIGIN_TO_RECIPIENT, LegAssignmentStatus.UNASSIGNED,
                null, null, null, null, null, null,
                13.4530, -16.5775, "Banjul Phones",
                13.4780, -16.6810, "Atlantic Road, Bakau New Town",
                "12.100", "180.00", "GMD", null);

        leg(cat, "shp-1012", 1, LegType.ORIGIN_TO_PICKUP, LegAssignmentStatus.IN_PROGRESS,
                "jainaba", cat.hoursAgo(6), cat.hoursAgo(6).plusMinutes(3), null,
                cat.hoursAgo(4), null,
                13.4390, -16.6790, "Serrekunda Home",
                13.4405, -16.6775, "Westfield Corner Pharmacy",
                "1.900", "150.00", "GMD", null);
        leg(cat, "shp-1012", 2, LegType.PICKUP_TO_RECIPIENT, LegAssignmentStatus.UNASSIGNED,
                null, null, null, null, null, null,
                13.4405, -16.6775, "Westfield Corner Pharmacy",
                13.4405, -16.6775, "Collected in person",
                "0.000", "0.00", "GMD", null);
    }

    private ShipmentLeg leg(SeedCatalogue cat, String shipmentKey, int sequence, LegType type,
                            LegAssignmentStatus status, String driverKey,
                            LocalDateTime offeredAt, LocalDateTime acceptedAt,
                            LocalDateTime declinedAt, LocalDateTime startedAt,
                            LocalDateTime completedAt,
                            Double originLat, Double originLng, String originLabel,
                            Double destLat, Double destLng, String destLabel,
                            String distanceKm, String earning, String currency,
                            String declineReason) {
        PickupPoint origin = pickupPointNamed(cat, originLabel);
        PickupPoint destination = pickupPointNamed(cat, destLabel);
        ShipmentLeg leg = ShipmentLeg.builder()
                .shipment(cat.shipment(shipmentKey)).sequence(sequence)
                .legType(type).assignmentStatus(status)
                .driver(driverKey == null ? null : cat.driver(driverKey))
                .offeredAt(offeredAt).acceptedAt(acceptedAt).declinedAt(declinedAt)
                .startedAt(startedAt).completedAt(completedAt)
                .declineReason(declineReason)
                .originLatitude(originLat).originLongitude(originLng).originLabel(originLabel)
                .destinationLatitude(destLat).destinationLongitude(destLng)
                .destinationLabel(destLabel)
                .originPickupPoint(origin).destinationPickupPoint(destination)
                .distanceKm(new BigDecimal(distanceKm)).earning(new BigDecimal(earning))
                .earningCurrency(currency)
                .build();
        return cat.save(leg);
    }

    /** Ties a leg end to a real pickup point when the label names one. */
    private PickupPoint pickupPointNamed(SeedCatalogue cat, String label) {
        if (label == null) {
            return null;
        }
        if (label.startsWith("Brikama Market")) {
            return cat.pickupPoint("brikama");
        }
        if (label.startsWith("Westfield")) {
            return cat.pickupPoint("westfield");
        }
        return null;
    }

    // ── Custody events ───────────────────────────────────────────────────────

    /**
     * Every handover, with what was presented at it.
     *
     * <p>Each of the eight event types appears at least once. The geofence
     * columns are the ones worth looking at: {@code metresFromExpected} on the
     * safe-drop event is 41 metres, and {@code withinGeofence} is false on the
     * failed attempt, because a driver who reports a failure from the other side
     * of town is a different fact from one who reports it at the door.
     */
    private void custodyEvents(SeedCatalogue cat) {
        // SJL-1001, the full chain: shop → driver → recipient.
        event(cat, "shp-1001", CustodyEventType.ARRIVED_AT_ORIGIN, "ebrima", null,
                null, cat.daysAgo(10).minusMinutes(20), 13.4530, -16.5775, "6.00", "4.00",
                true, null, null, null, "At the shop.", "ce-sample-0001", false);
        event(cat, "shp-1001", CustodyEventType.COLLECTED, "ebrima", "fatou",
                "482913", cat.daysAgo(10), 13.4530, -16.5775, "5.00", "3.00",
                true, "https://cdn.sujula.gm/sample/custody/1001-collected.jpg", null,
                null, "Vendor release code presented by the shop.", "ce-sample-0002", false);
        event(cat, "shp-1001", CustodyEventType.RELEASED, "ebrima", null,
                "730154", cat.daysAgo(9), 13.4383, -16.6781, "8.00", "11.00",
                true, "https://cdn.sujula.gm/sample/custody/1001-delivered.jpg",
                "https://cdn.sujula.gm/sample/custody/1001-signature.png",
                null, "Code read back by Aji Ceesay at the door.", "ce-sample-0003", false);

        // SJL-1002, collected and on its way.
        event(cat, "shp-1002", CustodyEventType.COLLECTED, "ebrima", "fatou",
                "119274", cat.daysAgo(2), 13.4530, -16.5775, "6.00", "2.00",
                true, null, null, null, "Second parcel of the round.",
                "ce-sample-0004", false);

        // SJL-1003, deposited at a counter and still there.
        event(cat, "shp-1004", CustodyEventType.COLLECTED, "saikou", "fatou",
                "554021", cat.daysAgo(4).plusHours(3), 13.4530, -16.5775, "7.00", "5.00",
                true, null, null, null, null, "ce-sample-0005", false);
        event(cat, "shp-1004", CustodyEventType.DEPOSITED, "saikou", "musa",
                "902318", cat.daysAgo(3), 13.2710, -16.6490, "9.00", "6.00",
                true, "https://cdn.sujula.gm/sample/custody/1003-shelf.jpg", null,
                null, "Shelf B-14. Storage runs to the 22nd.", "ce-sample-0006", false);

        // SJL-1004, an attempt that failed. Recorded at the door, not from the
        // depot — which is what the geofence columns are for.
        event(cat, "shp-1005", CustodyEventType.COLLECTED, "ebrima", "awa",
                "667410", cat.hoursAgo(26), 13.4390, -16.6790, "5.00", "4.00",
                true, null, null, null, null, "ce-sample-0007", false);
        event(cat, "shp-1005", CustodyEventType.FAILED_ATTEMPT, "ebrima", null,
                null, cat.hoursAgo(20), 13.4549, -16.5790, "12.00", "9.00",
                true, "https://cdn.sujula.gm/sample/custody/1004-nobody-home.jpg", null,
                "NOBODY_HOME", "Knocked twice, called the number on the order, no answer.",
                "ce-sample-0008", false);

        // SJL-1006, delivered in Dakar. Recorded offline and synced later: the
        // driver had no signal in the stairwell.
        event(cat, "shp-1007", CustodyEventType.COLLECTED, "aminata", "omar",
                "310928", cat.daysAgo(17).plusHours(4), 14.6690, -17.4370, "10.00", "7.00",
                true, null, null, null, null, "ce-sample-0009", false);
        event(cat, "shp-1007", CustodyEventType.RELEASED, "aminata", "cheikh",
                "845219", cat.daysAgo(16), 14.6937, -17.4441, "15.00", "22.00",
                true, "https://cdn.sujula.gm/sample/custody/1006-delivered.jpg",
                "https://cdn.sujula.gm/sample/custody/1006-signature.png",
                null, "Remis en main propre.", "ce-sample-0010", true);

        // SJL-1007, three failures and a return.
        event(cat, "shp-1008", CustodyEventType.COLLECTED, "saikou", "lamin",
                "228740", cat.daysAgo(23), 13.4470, -16.6950, "8.00", "6.00",
                true, null, null, null, null, "ce-sample-0011", false);
        event(cat, "shp-1008", CustodyEventType.FAILED_ATTEMPT, "saikou", null,
                null, cat.daysAgo(22), 13.4470, -16.6950, "20.00", "14.00",
                true, null, null, "NOBODY_HOME", "First attempt.", "ce-sample-0012", false);
        event(cat, "shp-1008", CustodyEventType.FAILED_ATTEMPT, "saikou", null,
                null, cat.daysAgo(21), 13.4470, -16.6950, "18.00", "10.00",
                true, null, null, "NOBODY_HOME", "Second attempt.", "ce-sample-0013", false);
        // The third failure was filed from 2.4 km away. Recorded as it was
        // reported, with the distance on the row, rather than quietly accepted.
        event(cat, "shp-1008", CustodyEventType.FAILED_ATTEMPT, "saikou", null,
                null, cat.daysAgo(20), 13.4650, -16.7050, "35.00", "2400.00",
                false, null, null, "NOBODY_HOME", "Third attempt.", "ce-sample-0014", false);
        event(cat, "shp-1008", CustodyEventType.DEPOSITED, "saikou", "musa",
                "441097", cat.daysAgo(20).plusHours(1), 13.4405, -16.6775, "9.00", "5.00",
                true, null, null, null, "Held at Westfield while the shop was shut.",
                "ce-sample-0015", false);
        event(cat, "shp-1008", CustodyEventType.REDISPATCHED, "musa", "saikou",
                "778215", cat.daysAgo(19).minusHours(2), 13.4405, -16.6775, "9.00", "4.00",
                true, null, null, null, "Back out for return to the seller.",
                "ce-sample-0016", false);
        event(cat, "shp-1008", CustodyEventType.RETURNED, "saikou", "lamin",
                "556331", cat.daysAgo(19), 13.4470, -16.6950, "7.00", "3.00",
                true, "https://cdn.sujula.gm/sample/custody/1007-returned.jpg", null,
                "UNDELIVERABLE", "Returned to Kololi Style; seller signed for it.",
                "ce-sample-0017", false);

        // SJL-1008, the driver has arrived and nothing has changed hands.
        event(cat, "shp-1009", CustodyEventType.ARRIVED_AT_ORIGIN, "jainaba", null,
                null, cat.hoursAgo(1), 13.4530, -16.5775, "6.00", "2.00",
                true, null, null, null, "Waiting while it is packed.",
                "ce-sample-0018", false);

        // SJL-1011, driver to driver mid-round — the transfer nobody remembers
        // to model until a parcel goes missing between two people.
        event(cat, "shp-1012", CustodyEventType.COLLECTED, "jainaba", "awa",
                "903472", cat.hoursAgo(4), 13.4390, -16.6790, "6.00", "3.00",
                true, null, null, null, null, "ce-sample-0019", false);
        event(cat, "shp-1012", CustodyEventType.TRANSFERRED, "jainaba", "ebrima",
                "216885", cat.hoursAgo(2), 13.4400, -16.6790, "11.00", "60.00",
                true, "https://cdn.sujula.gm/sample/custody/1011-transfer.jpg", null,
                null, "Handed to Ebrima at Westfield junction; bicycle could not take the load.",
                "ce-sample-0020", false);
    }

    private void event(SeedCatalogue cat, String shipmentKey, CustodyEventType type,
                       String recordedByKey, String counterpartyKey, String code,
                       LocalDateTime occurredAt, Double latitude, Double longitude,
                       String accuracyMetres, String metresFromExpected, boolean withinGeofence,
                       String photoUrl, String signatureUrl, String reasonCode, String note,
                       String clientEventId, boolean capturedOffline) {
        cat.save(CustodyEvent.builder()
                .shipment(cat.shipment(shipmentKey)).type(type)
                .recordedByUserId(cat.user(recordedByKey).getId())
                .counterpartyUserId(counterpartyKey == null ? null : cat.user(counterpartyKey).getId())
                .codePresented(code)
                .latitude(latitude).longitude(longitude)
                .accuracyMetres(new BigDecimal(accuracyMetres))
                .metresFromExpected(new BigDecimal(metresFromExpected))
                .withinGeofence(withinGeofence)
                .photoUrl(photoUrl).signatureUrl(signatureUrl)
                .reasonCode(reasonCode).note(note)
                .occurredAt(occurredAt)
                .capturedOffline(capturedOffline).clientEventId(clientEventId)
                .build());
    }

    // ── Parcel access codes ──────────────────────────────────────────────────

    /**
     * The code a recipient reads out, and nothing else.
     *
     * <p>No login, no app, no link in an email. This is the whole of C5: the
     * sister in Serrekunda has a phone number, and the code goes to it.
     */
    private Map<String, ParcelAccessCode> parcelAccessCodes(SeedCatalogue cat) {
        Map<String, ParcelAccessCode> codes = new LinkedHashMap<>();
        codes.put("shp-1001", cat.save(ParcelAccessCode.builder()
                .shipment(cat.shipment("shp-1001")).code("730154")
                .failedAttempts(0)
                .expiresAt(cat.daysAgo(8)).lastUsedAt(cat.daysAgo(9))
                .instructionsGiven(1).sentTo("+2207700100")
                .build()));
        codes.put("shp-1002", cat.save(ParcelAccessCode.builder()
                .shipment(cat.shipment("shp-1002")).code("664281")
                .failedAttempts(0)
                .expiresAt(cat.daysAhead(3))
                .instructionsGiven(0).sentTo("+2207700100")
                .build()));
        // Two wrong codes at the counter already. A third invalidates it.
        codes.put("shp-1004", cat.save(ParcelAccessCode.builder()
                .shipment(cat.shipment("shp-1004")).code("118902")
                .failedAttempts(2)
                .expiresAt(cat.daysAhead(2)).lastUsedAt(cat.hoursAgo(5))
                .instructionsGiven(2).sentTo("+2207700200")
                .build()));
        codes.put("shp-1005", cat.save(ParcelAccessCode.builder()
                .shipment(cat.shipment("shp-1005")).code("450127")
                .failedAttempts(0)
                .expiresAt(cat.daysAhead(1))
                .instructionsGiven(1).sentTo("+2207200111")
                .build()));
        // Burnt: too many wrong tries, and a new one has to be issued.
        codes.put("shp-1008", cat.save(ParcelAccessCode.builder()
                .shipment(cat.shipment("shp-1008")).code("903318")
                .failedAttempts(3).invalidatedAt(cat.daysAgo(21))
                .expiresAt(cat.daysAgo(20)).lastUsedAt(cat.daysAgo(21))
                .instructionsGiven(3).sentTo("+2207700300")
                .build()));
        codes.put("shp-1012", cat.save(ParcelAccessCode.builder()
                .shipment(cat.shipment("shp-1012")).code("287640")
                .failedAttempts(0)
                .expiresAt(cat.daysAhead(5))
                .instructionsGiven(1).sentTo("+2207700500")
                .build()));
        // Expired before anybody used it.
        codes.put("shp-1007", cat.save(ParcelAccessCode.builder()
                .shipment(cat.shipment("shp-1007")).code("845219")
                .failedAttempts(0)
                .expiresAt(cat.daysAgo(15)).lastUsedAt(cat.daysAgo(16))
                .instructionsGiven(1).sentTo("+221770333444")
                .build()));
        return codes;
    }

    // ── Recipient instructions ───────────────────────────────────────────────

    /**
     * What the recipient told the driver to do, proved by the code.
     *
     * <p>Every one of these names the code it was verified against. An
     * instruction that anybody could give by knowing a tracking number is not an
     * instruction, it is an invitation — so the row cannot exist without the
     * verification that produced it.
     */
    private void recipientInstructions(SeedCatalogue cat, Map<String, ParcelAccessCode> codes) {
        cat.save(RecipientInstruction.builder()
                .shipment(cat.shipment("shp-1002"))
                .type(RecipientInstructionType.RESCHEDULE)
                .windowFrom(cat.daysAhead(1).withHour(14).withMinute(0))
                .windowUntil(cat.daysAhead(1).withHour(17).withMinute(0))
                .verifiedByCodeId(codes.get("shp-1002").getId()).verifiedAt(cat.hoursAgo(8))
                .summary("Come tomorrow afternoon; she is at work until two.")
                .build());
        cat.save(RecipientInstruction.builder()
                .shipment(cat.shipment("shp-1005"))
                .type(RecipientInstructionType.AUTHORISE_SAFE_DROP)
                .safeDropLocation("Behind the blue gate, under the bench")
                .safeDropPerson("Neighbour, Aunt Nyima")
                .verifiedByCodeId(codes.get("shp-1005").getId()).verifiedAt(cat.hoursAgo(12))
                .summary("Leave it with Aunt Nyima next door if nobody answers.")
                .build());
        cat.save(RecipientInstruction.builder()
                .shipment(cat.shipment("shp-1008"))
                .type(RecipientInstructionType.CHOOSE_PICKUP_POINT)
                .pickupPoint(cat.pickupPoint("westfield"))
                .verifiedByCodeId(codes.get("shp-1008").getId()).verifiedAt(cat.daysAgo(21))
                .summary("Hold it at Westfield instead of trying the house again.")
                .build());
        // Superseded: she changed her mind and asked for a pickup point after
        // asking for a later window. Both rows stay, so the sequence of
        // instructions remains readable.
        cat.save(RecipientInstruction.builder()
                .shipment(cat.shipment("shp-1008"))
                .type(RecipientInstructionType.RESCHEDULE)
                .windowFrom(cat.daysAgo(21).withHour(9).withMinute(0))
                .windowUntil(cat.daysAgo(21).withHour(12).withMinute(0))
                .verifiedByCodeId(codes.get("shp-1008").getId()).verifiedAt(cat.daysAgo(22))
                .supersededAt(cat.daysAgo(21))
                .summary("Morning delivery — replaced by the pickup-point request.")
                .build());
        cat.save(RecipientInstruction.builder()
                .shipment(cat.shipment("shp-1012"))
                .type(RecipientInstructionType.CHOOSE_PICKUP_POINT)
                .pickupPoint(cat.pickupPoint("westfield"))
                .verifiedByCodeId(codes.get("shp-1012").getId()).verifiedAt(cat.hoursAgo(6))
                .summary("Westfield Corner Pharmacy — she passes it on the way home.")
                .build());
    }

    // ── Deliveries ───────────────────────────────────────────────────────────

    /**
     * The per-item delivery record, in every status it holds.
     *
     * <p>One row per order item, one-to-one, which is why there are as many of
     * these as there are lines being moved rather than as many as there are
     * shipments.
     */
    private void deliveries(SeedCatalogue cat) {
        delivery(cat, "del-1001", "1001-spark10", DeliveryStatus.DELIVERED, "SJLD-0000001001",
                "ebrima", null, "Aji Ceesay", "+2207700100", "9.400", "210.00",
                cat.daysAgo(11), cat.daysAgo(10), null, null, cat.daysAgo(9), cat.daysAgo(9),
                "Handed over against a code at the door.",
                "https://cdn.sujula.gm/sample/custody/1001-delivered.jpg",
                "https://cdn.sujula.gm/sample/custody/1001-signature.png", false);

        delivery(cat, "del-1002", "1002-spark10", DeliveryStatus.OUT_FOR_DELIVERY, "SJLD-0000001002",
                "ebrima", null, "Aji Ceesay", "+2207700100", "9.400", "210.00",
                cat.daysAgo(3), cat.daysAgo(2), null, cat.hoursAgo(3), null, null,
                "Second attempt window this afternoon.", null, null, false);

        delivery(cat, "del-1003", "1002-a15", DeliveryStatus.ASSIGNED, "SJLD-0000001003",
                "aminata", null, "Aji Ceesay", "+2207700100", "214.600", "12000",
                cat.daysAgo(3), null, null, null, null, null,
                "Cross-border; leaves once the seller has packed it.", null, null, false);

        delivery(cat, "del-1004", "1003-hot30", DeliveryStatus.AT_PICKUP_POINT, "SJLD-0000001004",
                "saikou", "brikama", "Mariama Jallow", "+2207700200", "34.200", "480.00",
                cat.daysAgo(4), cat.daysAgo(4).plusHours(3), cat.daysAgo(3), null, null, null,
                "Shelf B-14 at Brikama Market Stationers.", null, null, false);

        delivery(cat, "del-1005", "1004-pot", DeliveryStatus.FAILED, "SJLD-0000001005",
                "ebrima", null, "Binta Touray", "+2207200111", "11.700", "150.00",
                cat.hoursAgo(28), cat.hoursAgo(26), null, cat.hoursAgo(22), null, null,
                "Nobody home; retry booked for tomorrow morning.",
                "https://cdn.sujula.gm/sample/custody/1004-nobody-home.jpg", null, true);

        delivery(cat, "del-1006", "1005-blender", DeliveryStatus.PENDING, "SJLD-0000001006",
                null, null, "Binta Touray", "+2207200111", "11.700", "220.00",
                null, null, null, null, null, null,
                "Cancelled before anybody was assigned.", null, null, false);

        delivery(cat, "del-1007", "1006-a15", DeliveryStatus.DELIVERED, "SJLD-0000001007",
                "aminata", null, "Cheikh Ndiaye", "+221770333444", "4.100", "1500",
                cat.daysAgo(17), cat.daysAgo(17).plusHours(4), null, null,
                cat.daysAgo(16), cat.daysAgo(16),
                "Remis en main propre.",
                "https://cdn.sujula.gm/sample/custody/1006-delivered.jpg",
                "https://cdn.sujula.gm/sample/custody/1006-signature.png", false);

        delivery(cat, "del-1008", "1006-powerbank", DeliveryStatus.DELIVERED, "SJLD-0000001008",
                "aminata", null, "Cheikh Ndiaye", "+221770333444", "4.100", "0",
                cat.daysAgo(17), cat.daysAgo(17).plusHours(4), null, null,
                cat.daysAgo(16), cat.daysAgo(16),
                "Second parcel, same round.", null, null, false);

        delivery(cat, "del-1009", "1007-wax", DeliveryStatus.RETURNED, "SJLD-0000001009",
                "saikou", "westfield", "Fatoumata Mendy", "+2207700300", "1.200", "150.00",
                cat.daysAgo(24), cat.daysAgo(23), cat.daysAgo(20), null, null, null,
                "Three attempts, then returned to the seller.", null, null, false);

        delivery(cat, "del-1010", "1008-spark10", DeliveryStatus.PREPARED, "SJLD-0000001010",
                "jainaba", null, "Ousainou Bojang", "+2207700400", "10.800", "150.00",
                cat.hoursAgo(2), null, null, null, null, null,
                "Packed; the driver is in the shop waiting for it.", null, null, false);

        delivery(cat, "del-1011", "1009-iphone11", DeliveryStatus.PENDING, "SJLD-0000001011",
                null, null, "Mariama Jallow", "+2207700200", "34.900", "480.00",
                null, null, null, null, null, null,
                "Offered to a driver; nobody has accepted yet.", null, null, true);

        delivery(cat, "del-1012", "1011-pot", DeliveryStatus.IN_TRANSIT, "SJLD-0000001012",
                "jainaba", "westfield", "Kaddy Secka", "+2207700500", "1.900", "150.00",
                cat.hoursAgo(6), cat.hoursAgo(4), null, null, null, null,
                "On the way to Westfield; she collects there.", null, null, false);

        delivery(cat, "del-1013", "1010-parts", DeliveryStatus.PICKED_UP, "SJLD-0000001013",
                "ebrima", null, "Yankuba Bah", "+2207200222", "12.100", "180.00",
                cat.hoursAgo(7), cat.hoursAgo(6), null, null, null, null,
                "Collected before the payment failure was noticed.", null, null, false);
    }

    private void delivery(SeedCatalogue cat, String key, String orderItemKey, DeliveryStatus status,
                          String trackingNumber, String driverKey, String pickupPointKey,
                          String recipientName, String recipientPhone,
                          String distanceKm, String earning,
                          LocalDateTime assignedAt, LocalDateTime pickedUpAt,
                          LocalDateTime arrivedAtPickupPointAt, LocalDateTime outForDeliveryAt,
                          LocalDateTime deliveredAt, LocalDateTime actualDeliveredAt,
                          String notes, String proofImageUrl, String signatureUrl,
                          boolean contactlessRequested) {
        Delivery delivery = Delivery.builder()
                .orderItem(cat.orderItem(orderItemKey))
                .driver(driverKey == null ? null : cat.driver(driverKey))
                .pickupPoint(pickupPointKey == null ? null : cat.pickupPoint(pickupPointKey))
                .status(status).trackingNumber(trackingNumber)
                .recipientName(recipientName).recipientPhone(recipientPhone)
                .distanceKm(new BigDecimal(distanceKm)).earningAmount(new BigDecimal(earning))
                .estimatedDeliveryAt(deliveredAt != null ? deliveredAt : cat.daysAhead(2))
                .estimatedDeliveryDate(deliveredAt != null ? deliveredAt : cat.daysAhead(2))
                .actualDeliveredAt(actualDeliveredAt)
                .contactlessRequested(contactlessRequested)
                .assignedAt(assignedAt).pickedUpAt(pickedUpAt)
                .arrivedAtPickupPointAt(arrivedAtPickupPointAt)
                .outForDeliveryAt(outForDeliveryAt).deliveredAt(deliveredAt)
                .notes(notes)
                .deliveryProofImageUrl(proofImageUrl).recipientSignatureUrl(signatureUrl)
                .build();
        cat.deliveries.put(key, cat.save(delivery));
    }

    // ── Handover codes ───────────────────────────────────────────────────────

    /**
     * One code per handover, of every type the chain can need.
     *
     * <p>A single "delivery code" would collapse nine different handovers into
     * one secret: the code a shop reads to release a parcel to a driver is not
     * the code a recipient reads to accept it, and a system where they are the
     * same is one where a driver who has collected can also sign for it.
     */
    private void handoverCodes(SeedCatalogue cat) {
        code(cat, HandoverCodeType.VENDOR_RELEASE, "482913", "shp-1001", "1001-bp", null,
                true, "fatou", cat.daysAgo(10), cat.daysAgo(9), 0, null);
        code(cat, HandoverCodeType.VENDOR_TO_DRIVER, "119274", "shp-1002", "1002-bp", "del-1002",
                true, "fatou", cat.daysAgo(2), cat.daysAgo(1), 0, null);
        code(cat, HandoverCodeType.RECIPIENT_RELEASE, "730154", "shp-1001", "1001-bp", "del-1001",
                true, "isatou", cat.daysAgo(9), cat.daysAgo(8), 0, null);
        code(cat, HandoverCodeType.VENDOR_TO_PICKUP, "554021", "shp-1004", "1003-bp", "del-1004",
                true, "fatou", cat.daysAgo(4), cat.daysAgo(3), 0, null);
        code(cat, HandoverCodeType.DRIVER_TO_PICKUP, "902318", "shp-1004", "1003-bp", "del-1004",
                true, "musa", cat.daysAgo(3), cat.daysAgo(2), 1, null);
        // Live, unused, and two wrong tries against it at the counter.
        code(cat, HandoverCodeType.PICKUP_TO_CUSTOMER, "118902", "shp-1004", "1003-bp", "del-1004",
                false, null, null, cat.daysAhead(2), 2, null);
        code(cat, HandoverCodeType.DRIVER_TO_CUSTOMER, "450127", "shp-1005", "1004-sh", "del-1005",
                false, null, null, cat.daysAhead(1), 0, null);
        code(cat, HandoverCodeType.DRIVER_TO_DRIVER, "216885", "shp-1012", "1011-sh", "del-1012",
                true, "ebrima", cat.hoursAgo(2), cat.hoursAhead(4), 0, null);
        code(cat, HandoverCodeType.PICKUP_TO_DRIVER, "778215", "shp-1008", "1007-ks", "del-1009",
                true, "saikou", cat.daysAgo(19).minusHours(2), cat.daysAgo(18), 0, null);
        // Invalidated after three wrong attempts. Not merely expired — somebody
        // was guessing, and a replacement had to be issued.
        code(cat, HandoverCodeType.RECIPIENT_RELEASE, "903318", "shp-1008", "1007-ks", "del-1009",
                false, null, null, cat.daysAgo(20), 3, cat.daysAgo(21));
        // Expired unused: the collection window closed.
        code(cat, HandoverCodeType.PICKUP_TO_CUSTOMER, "667019", "shp-1008", "1007-ks", "del-1009",
                false, null, null, cat.daysAgo(19), 0, null);
    }

    private void code(SeedCatalogue cat, HandoverCodeType type, String value, String shipmentKey,
                      String vendorOrderKey, String deliveryKey, boolean used, String usedByKey,
                      LocalDateTime usedAt, LocalDateTime expiresAt, int failedAttempts,
                      LocalDateTime invalidatedAt) {
        cat.save(HandoverCode.builder()
                .codeType(type).code(value)
                .shipment(cat.shipment(shipmentKey))
                .vendorOrder(cat.vendorOrder(vendorOrderKey))
                .delivery(deliveryKey == null ? null : cat.delivery(deliveryKey))
                .used(used)
                .usedByUserId(usedByKey == null ? null : cat.user(usedByKey).getId())
                .usedAt(usedAt).expiresAt(expiresAt)
                .failedAttempts(failedAttempts).invalidatedAt(invalidatedAt)
                .build());
    }

    // ── Tracking events on a delivery ────────────────────────────────────────

    private void deliveryTracking(SeedCatalogue cat) {
        track(cat, "del-1001", DeliveryStatus.ASSIGNED, 13.4530, -16.5775,
                "Assigned to Ebrima Colley.", "ebrima");
        track(cat, "del-1001", DeliveryStatus.PICKED_UP, 13.4530, -16.5775,
                "Collected from Banjul Phones.", "ebrima");
        track(cat, "del-1001", DeliveryStatus.IN_TRANSIT, 13.4460, -16.6210,
                "On Kairaba Avenue.", "ebrima");
        track(cat, "del-1001", DeliveryStatus.OUT_FOR_DELIVERY, 13.4400, -16.6700,
                "Two stops away.", "ebrima");
        track(cat, "del-1001", DeliveryStatus.DELIVERED, 13.4383, -16.6781,
                "Code presented by Aji Ceesay.", "ebrima");

        track(cat, "del-1004", DeliveryStatus.PICKED_UP, 13.4530, -16.5775,
                "Collected for the Brikama run.", "saikou");
        track(cat, "del-1004", DeliveryStatus.IN_TRANSIT, 13.3500, -16.6600,
                "Coast road.", "saikou");
        track(cat, "del-1004", DeliveryStatus.AT_PICKUP_POINT, 13.2710, -16.6490,
                "Left on shelf B-14.", "saikou");

        track(cat, "del-1005", DeliveryStatus.PICKED_UP, 13.4390, -16.6790,
                "Collected from Serrekunda Home.", "ebrima");
        track(cat, "del-1005", DeliveryStatus.FAILED, 13.4549, -16.5790,
                "Nobody home. Retry booked.", "ebrima");

        track(cat, "del-1009", DeliveryStatus.FAILED, 13.4470, -16.6950,
                "Third failed attempt.", "saikou");
        track(cat, "del-1009", DeliveryStatus.RETURNED, 13.4470, -16.6950,
                "Returned to Kololi Style.", "saikou");

        track(cat, "del-1012", DeliveryStatus.PICKED_UP, 13.4390, -16.6790,
                "Collected by Jainaba Drammeh.", "jainaba");
        track(cat, "del-1012", DeliveryStatus.IN_TRANSIT, 13.4400, -16.6790,
                "Handed to Ebrima at Westfield junction.", "jainaba");

        // Recorded by nobody: a position the tracker reported with no person
        // behind it. The column is nullable for exactly this.
        track(cat, "del-1002", DeliveryStatus.IN_TRANSIT, 13.4450, -16.6400,
                "Automatic position report.", null);
    }

    private void track(SeedCatalogue cat, String deliveryKey, DeliveryStatus status,
                       Double latitude, Double longitude, String description, String byKey) {
        cat.save(DeliveryTracking.builder()
                .delivery(cat.delivery(deliveryKey)).status(status)
                .latitude(latitude).longitude(longitude)
                .description(description)
                .recordedBy(byKey == null ? null : cat.user(byKey))
                .build());
    }

    // ── Proof of delivery ────────────────────────────────────────────────────

    private void proofOfDelivery(SeedCatalogue cat) {
        cat.save(ProofOfDelivery.builder()
                .deliveryId(cat.delivery("del-1001").getId())
                .imageUrl("https://cdn.sujula.gm/sample/custody/1001-delivered.jpg")
                .signatureUrl("https://cdn.sujula.gm/sample/custody/1001-signature.png")
                .latitude(13.4383).longitude(-16.6781)
                .notes("Handed to Aji Ceesay; code 730154 presented.")
                .submittedAt(cat.daysAgo(9))
                .build());
        cat.save(ProofOfDelivery.builder()
                .deliveryId(cat.delivery("del-1007").getId())
                .imageUrl("https://cdn.sujula.gm/sample/custody/1006-delivered.jpg")
                .signatureUrl("https://cdn.sujula.gm/sample/custody/1006-signature.png")
                .latitude(14.6937).longitude(-17.4441)
                .notes("Remis en main propre; code 845219.")
                .submittedAt(cat.daysAgo(16))
                .build());
        // A photograph and no signature. Nobody signs for a parcel left at a
        // counter, and requiring one would make the pickup route impossible.
        cat.save(ProofOfDelivery.builder()
                .deliveryId(cat.delivery("del-1008").getId())
                .imageUrl("https://cdn.sujula.gm/sample/custody/1006-second-parcel.jpg")
                .latitude(14.6937).longitude(-17.4441)
                .notes("Second parcel of the same order.")
                .submittedAt(cat.daysAgo(16))
                .build());
        // The evidence for a failure. Proof is not only for success.
        cat.save(ProofOfDelivery.builder()
                .deliveryId(cat.delivery("del-1005").getId())
                .imageUrl("https://cdn.sujula.gm/sample/custody/1004-nobody-home.jpg")
                .latitude(13.4549).longitude(-16.5790)
                .notes("Locked gate, no answer. Photographed at the address.")
                .submittedAt(cat.hoursAgo(20))
                .build());
        cat.save(ProofOfDelivery.builder()
                .deliveryId(cat.delivery("del-1009").getId())
                .imageUrl("https://cdn.sujula.gm/sample/custody/1007-returned.jpg")
                .latitude(13.4470).longitude(-16.6950)
                .notes("Returned to the seller, who signed for it.")
                .submittedAt(cat.daysAgo(19))
                .build());
        // No position at all: the phone had no fix indoors. Recorded as missing
        // rather than as a plausible guess.
        cat.save(ProofOfDelivery.builder()
                .deliveryId(cat.delivery("del-1004").getId())
                .imageUrl("https://cdn.sujula.gm/sample/custody/1003-shelf.jpg")
                .notes("Shelf B-14. No GPS fix inside the shop.")
                .submittedAt(cat.daysAgo(3))
                .build());
    }
}
