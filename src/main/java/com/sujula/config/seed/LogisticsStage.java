package com.sujula.config.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;

import org.springframework.stereotype.Component;

import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.VehicleType;
import com.sujula.model.delivery.DeliveryContext;
import com.sujula.model.delivery.DeliveryRoute;
import com.sujula.model.delivery.Driver;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.logistics.DeliveryRateCard;
import com.sujula.model.logistics.DeliveryZone;

/**
 * Where this marketplace delivers, what it charges to, and who carries it.
 *
 * <p>All of it hangs off the delivery context rather than the payer's — C1's
 * rule, made concrete. The sampled contexts include ones with no user attached
 * at all, because a shopper browsing anonymously still has a destination and the
 * catalogue still has to rank against it.
 *
 * <p>Zones carry real GeoJSON, in GeoJSON's own {@code [longitude, latitude]}
 * order, and the bounding boxes below agree with the rings. A zone whose stored
 * box disagrees with its shape is a zone that silently contains nothing.
 */
@Component
class LogisticsStage implements SeedStage {

    @Override
    public String name() {
        return "Zones, rate cards, pickup points and drivers";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        zones(cat);
        cat.flush();
        rateCards(cat);
        pickupPoints(cat);
        cat.flush();
        drivers(cat);
        deliveryContexts(cat);
        cat.flush();
        routes(cat);
    }

    // ── Zones ────────────────────────────────────────────────────────────────

    private void zones(SeedCatalogue cat) {
        zone(cat, "banjul", "GM-BANJUL", "Banjul island", "GM",
                "Banjul proper, out to Denton Bridge.",
                -16.6000, -16.5500, 13.4400, 13.4750, true, null, 10, true);
        zone(cat, "kanifing", "GM-KANIFING", "Kanifing and Serrekunda", "GM",
                "Serrekunda, Bakau, Fajara, Kanifing.",
                -16.7200, -16.6300, 13.4100, 13.4900, true, null, 20, true);
        zone(cat, "westcoast", "GM-WESTCOAST", "West Coast Region", "GM",
                "Kololi, Brusubi, Brikama and the coast road.",
                -16.7800, -16.6000, 13.2000, 13.4600, true, null, 30, true);
        // Serviceable is a separate switch from active: this zone exists, rates
        // resolve against it, and nothing may be delivered into it yet. Merging
        // the two would leave the buyer with "no such place" instead of a
        // reason.
        zone(cat, "northbank", "GM-NORTHBANK", "North Bank Region", "GM",
                "Barra, Kerewan, Farafenni.",
                -16.2000, -15.9000, 13.4000, 13.6500, false,
                "No driver covers the north bank yet. Pickup-point collection only.", 40, true);
        // Retired. Kept so historical orders still name the place they went to.
        zone(cat, "upriver", "GM-UPRIVER", "Upper River (retired)", "GM",
                "Closed while the route is reconsidered.",
                -14.5000, -13.8000, 13.2000, 13.6000, false,
                "Route withdrawn.", 90, false);
        zone(cat, "dakar", "SN-DAKAR", "Dakar", "SN",
                "Plateau, Médina, Point E, Ouakam.",
                -17.5500, -17.3500, 14.6300, 14.8000, true, null, 10, true);
        zone(cat, "thies", "SN-THIES", "Thiès", "SN",
                "Thiès ville et environs.",
                -17.0000, -16.8000, 14.7200, 14.8500, true, null, 20, true);
    }

    private void zone(SeedCatalogue cat, String key, String code, String name, String country,
                      String description, double minLng, double maxLng, double minLat,
                      double maxLat, boolean serviceable, String unserviceableReason,
                      int priority, boolean active) {
        DeliveryZone zone = DeliveryZone.builder()
                .code(code).name(name).description(description).countryCode(country)
                .geometry(box(minLng, maxLng, minLat, maxLat))
                .serviceable(serviceable).unserviceableReason(unserviceableReason)
                .priority(priority).active(active)
                .lastEditedByUserId(cat.user("admin").getId())
                .build();
        // Five vertices, because a GeoJSON ring repeats its first point to close.
        zone.applyBounds(minLat, maxLat, minLng, maxLng, 5);
        cat.zones.put(key, cat.save(zone));
    }

    /** A rectangular ring, written the way GeoJSON wants it: longitude first. */
    private String box(double minLng, double maxLng, double minLat, double maxLat) {
        return "{\"type\":\"Polygon\",\"coordinates\":[[["
                + minLng + "," + minLat + "],["
                + maxLng + "," + minLat + "],["
                + maxLng + "," + maxLat + "],["
                + minLng + "," + maxLat + "],["
                + minLng + "," + minLat + "]]]}";
    }

    // ── What delivery costs ──────────────────────────────────────────────────

    /**
     * Rate cards, from the most specific to the fallback.
     *
     * <p>Specificity decides which one wins, so the sample has one of each
     * shape: zone and mode, zone alone, mode alone, country alone. It also has a
     * card that has already expired and one that starts next month, because a
     * lookup that ignores the date window is a lookup that quietly charges last
     * quarter's prices.
     */
    private void rateCards(SeedCatalogue cat) {
        LocalDate today = LocalDate.now();

        cat.save(DeliveryRateCard.builder()
                .name("Banjul — home delivery")
                .zone(cat.zone("banjul")).mode(DeliveryMode.HOME_DELIVERY).countryCode("GM")
                .currency("GMD")
                .baseFee(SeedCatalogue.money("120.00"))
                .includedKm(new BigDecimal("3.00")).perKm(SeedCatalogue.money("18.00"))
                .includedKg(new BigDecimal("2.00")).perKg(SeedCatalogue.money("25.00"))
                .minFee(SeedCatalogue.money("120.00")).maxFee(SeedCatalogue.money("900.00"))
                .freeAbove(SeedCatalogue.money("12000.00"))
                .effectiveFrom(today.minusDays(120)).active(true)
                .createdByUserId(cat.user("admin").getId())
                .build());

        cat.save(DeliveryRateCard.builder()
                .name("Banjul — collection from a pickup point")
                .zone(cat.zone("banjul")).mode(DeliveryMode.PICKUP_POINT).countryCode("GM")
                .currency("GMD")
                .baseFee(SeedCatalogue.money("60.00"))
                .includedKm(new BigDecimal("5.00")).perKm(SeedCatalogue.money("10.00"))
                .includedKg(new BigDecimal("3.00")).perKg(SeedCatalogue.money("15.00"))
                .minFee(SeedCatalogue.money("60.00"))
                .effectiveFrom(today.minusDays(120)).active(true)
                .createdByUserId(cat.user("admin").getId())
                .build());

        cat.save(DeliveryRateCard.builder()
                .name("Kanifing and Serrekunda")
                .zone(cat.zone("kanifing")).countryCode("GM")
                .currency("GMD")
                .baseFee(SeedCatalogue.money("150.00"))
                .includedKm(new BigDecimal("4.00")).perKm(SeedCatalogue.money("20.00"))
                .includedKg(new BigDecimal("2.00")).perKg(SeedCatalogue.money("25.00"))
                .minFee(SeedCatalogue.money("150.00")).maxFee(SeedCatalogue.money("1200.00"))
                .effectiveFrom(today.minusDays(120)).active(true)
                .createdByUserId(cat.user("admin").getId())
                .build());

        cat.save(DeliveryRateCard.builder()
                .name("West Coast Region")
                .zone(cat.zone("westcoast")).countryCode("GM")
                .currency("GMD")
                .baseFee(SeedCatalogue.money("220.00"))
                .includedKm(new BigDecimal("6.00")).perKm(SeedCatalogue.money("22.00"))
                .includedKg(new BigDecimal("2.00")).perKg(SeedCatalogue.money("30.00"))
                .minFee(SeedCatalogue.money("220.00")).maxFee(SeedCatalogue.money("1800.00"))
                .effectiveFrom(today.minusDays(90)).active(true)
                .createdByUserId(cat.user("admin").getId())
                .build());

        // CFA. Whole francs throughout, because there is no smaller unit to
        // charge in.
        cat.save(DeliveryRateCard.builder()
                .name("Dakar — livraison à domicile")
                .zone(cat.zone("dakar")).mode(DeliveryMode.HOME_DELIVERY).countryCode("SN")
                .currency("XOF")
                .baseFee(SeedCatalogue.wholeUnits("1500"))
                .includedKm(new BigDecimal("4.00")).perKm(SeedCatalogue.wholeUnits("250"))
                .includedKg(new BigDecimal("2.00")).perKg(SeedCatalogue.wholeUnits("300"))
                .minFee(SeedCatalogue.wholeUnits("1500")).maxFee(SeedCatalogue.wholeUnits("12000"))
                .freeAbove(SeedCatalogue.wholeUnits("150000"))
                .effectiveFrom(today.minusDays(100)).active(true)
                .createdByUserId(cat.user("admin").getId())
                .build());

        cat.save(DeliveryRateCard.builder()
                .name("Thiès")
                .zone(cat.zone("thies")).countryCode("SN")
                .currency("XOF")
                .baseFee(SeedCatalogue.wholeUnits("2000"))
                .includedKm(new BigDecimal("5.00")).perKm(SeedCatalogue.wholeUnits("275"))
                .includedKg(new BigDecimal("2.00")).perKg(SeedCatalogue.wholeUnits("325"))
                .effectiveFrom(today.minusDays(60)).active(true)
                .createdByUserId(cat.user("admin").getId())
                .build());

        // Country fallback: no zone, no mode. What a destination outside every
        // drawn polygon falls back to.
        cat.save(DeliveryRateCard.builder()
                .name("The Gambia — anywhere not in a zone")
                .countryCode("GM").currency("GMD")
                .baseFee(SeedCatalogue.money("350.00"))
                .includedKm(new BigDecimal("8.00")).perKm(SeedCatalogue.money("26.00"))
                .includedKg(new BigDecimal("2.00")).perKg(SeedCatalogue.money("35.00"))
                .minFee(SeedCatalogue.money("350.00"))
                .effectiveFrom(today.minusDays(200)).active(true)
                .note("Fallback. Deliberately dearer than any zone card.")
                .createdByUserId(cat.user("admin").getId())
                .build());

        // Expired last month. Must never be picked for a quote taken today.
        cat.save(DeliveryRateCard.builder()
                .name("Banjul — fuel surcharge (ended)")
                .zone(cat.zone("banjul")).mode(DeliveryMode.HOME_DELIVERY).countryCode("GM")
                .currency("GMD")
                .baseFee(SeedCatalogue.money("180.00"))
                .includedKm(new BigDecimal("3.00")).perKm(SeedCatalogue.money("24.00"))
                .includedKg(BigDecimal.ZERO).perKg(SeedCatalogue.money("25.00"))
                .effectiveFrom(today.minusDays(180)).effectiveUntil(today.minusDays(30))
                .active(true)
                .note("Withdrawn when the fuel price came back down.")
                .createdByUserId(cat.user("admin").getId())
                .build());

        // Agreed and not yet in force.
        cat.save(DeliveryRateCard.builder()
                .name("West Coast — vendor collection (from next month)")
                .zone(cat.zone("westcoast")).mode(DeliveryMode.VENDOR_PICKUP).countryCode("GM")
                .currency("GMD")
                .baseFee(SeedCatalogue.money("0.00"))
                .includedKm(BigDecimal.ZERO).perKm(BigDecimal.ZERO)
                .includedKg(BigDecimal.ZERO).perKg(BigDecimal.ZERO)
                .effectiveFrom(today.plusDays(30)).active(true)
                .note("Collection from the shop, free. Starts with the new terms.")
                .createdByUserId(cat.user("admin").getId())
                .build());

        // Switched off rather than deleted, so the decision stays visible.
        cat.save(DeliveryRateCard.builder()
                .name("North Bank (suspended)")
                .zone(cat.zone("northbank")).countryCode("GM")
                .currency("GMD")
                .baseFee(SeedCatalogue.money("500.00"))
                .includedKm(new BigDecimal("10.00")).perKm(SeedCatalogue.money("30.00"))
                .includedKg(BigDecimal.ZERO).perKg(SeedCatalogue.money("40.00"))
                .effectiveFrom(today.minusDays(150)).active(false)
                .note("Inactive while the zone is unserviceable.")
                .createdByUserId(cat.user("admin").getId())
                .build());
    }

    // ── Pickup points ────────────────────────────────────────────────────────

    /**
     * Counters a parcel can be left at.
     *
     * <p>These are C5's answer for a recipient who is out at work: somebody with
     * a phone and no account collects from a shop by reading a code back. So the
     * sample includes one that is full, one closed for a fortnight and one still
     * waiting for approval — the three reasons a chosen point cannot be used,
     * each of which the checkout has to explain rather than merely refuse.
     */
    private void pickupPoints(SeedCatalogue cat) {
        cat.pickupPoints.put("westfield", cat.save(PickupPoint.builder()
                .operatorUser(cat.user("musa")).status(PartnerStatus.APPROVED)
                .name("Westfield Corner Pharmacy")
                .addressStreet("Kairaba Avenue at Westfield Junction")
                .city("Serrekunda").state("Kanifing").countryCode("GM")
                .latitude(13.4405).longitude(-16.6775)
                .contactPhone("+2204400801").contactEmail("westfield@sujula.gm")
                .managerName("Musa Jarju")
                .openingHours("Mon–Sat 08:00–21:00; Sun 10:00–18:00")
                .profileImageUrl("https://cdn.sujula.gm/sample/pickup/westfield.jpg")
                .capacity(120).storageDays(7)
                .commissionPerParcel(SeedCatalogue.money("35.00")).commissionCurrency("GMD")
                .active(true)
                .totalEarnings(SeedCatalogue.money("4270.00"))
                .totalTransactions(122).monthlyDeliveries(31)
                .build()));

        // Full. Nothing more may be sent here until parcels are collected.
        PickupPoint brikama = PickupPoint.builder()
                .operatorUser(cat.user("musa")).status(PartnerStatus.APPROVED)
                .name("Brikama Market Stationers")
                .addressStreet("Brikama Market, stall row C")
                .city("Brikama").state("West Coast").countryCode("GM")
                .latitude(13.2710).longitude(-16.6490)
                .contactPhone("+2204400802")
                .managerName("Ousainou Bojang")
                .openingHours("Mon–Sat 09:00–19:00")
                .capacity(40).storageDays(5)
                .commissionPerParcel(SeedCatalogue.money("30.00")).commissionCurrency("GMD")
                .active(true)
                .totalEarnings(SeedCatalogue.money("1890.00"))
                .totalTransactions(63).monthlyDeliveries(18)
                .build();
        brikama.applyStoredCount(40);
        cat.pickupPoints.put("brikama", cat.save(brikama));

        // Closed for two weeks. Approved, active, and still unusable — three
        // separate fields, and collapsing any of them loses the reason.
        cat.pickupPoints.put("bakau", cat.save(PickupPoint.builder()
                .operatorUser(cat.user("musa")).status(PartnerStatus.APPROVED)
                .name("Bakau Newtown Hardware")
                .addressStreet("Atlantic Road, Bakau New Town")
                .city("Bakau").state("Kanifing").countryCode("GM")
                .latitude(13.4785).longitude(-16.6815)
                .contactPhone("+2204400803")
                .managerName("Sainey Faal")
                .openingHours("Mon–Fri 09:00–18:00")
                .capacity(60).storageDays(7)
                .commissionPerParcel(SeedCatalogue.money("30.00")).commissionCurrency("GMD")
                .active(true)
                .closedUntil(cat.daysAhead(14))
                .closureReason("Shop closed for refurbishment until the 30th.")
                .totalEarnings(SeedCatalogue.money("930.00"))
                .totalTransactions(31).monthlyDeliveries(0)
                .build()));

        cat.pickupPoints.put("banjul-ferry", cat.save(PickupPoint.builder()
                .status(PartnerStatus.PENDING)
                .name("Banjul Ferry Terminal Kiosk")
                .addressStreet("Ferry Terminal, Liberation Avenue")
                .city("Banjul").state("Banjul").countryCode("GM")
                .latitude(13.4540).longitude(-16.5785)
                .contactPhone("+2204400804")
                .managerName("Modou Jammeh")
                .capacity(50).storageDays(4)
                .commissionPerParcel(SeedCatalogue.money("30.00")).commissionCurrency("GMD")
                .active(true)
                .adminNote("Application received. Site visit not yet done.")
                .build()));

        cat.pickupPoints.put("dakar-plateau", cat.save(PickupPoint.builder()
                .operatorUser(cat.user("adama")).status(PartnerStatus.APPROVED)
                .name("Plateau Librairie")
                .addressStreet("Rue Mohamed V, face à la poste")
                .city("Dakar").state("Dakar").countryCode("SN")
                .latitude(14.6695).longitude(-17.4365)
                .contactPhone("+221338200801").contactEmail("plateau@sujula.sn")
                .managerName("Adama Diallo")
                .openingHours("Lun–Sam 08:30–20:00")
                .capacity(90).storageDays(7)
                .commissionPerParcel(SeedCatalogue.wholeUnits("500")).commissionCurrency("XOF")
                .active(true)
                .totalEarnings(SeedCatalogue.wholeUnits("28500"))
                .totalTransactions(57).monthlyDeliveries(14)
                .build()));

        cat.pickupPoints.put("thies-gare", cat.save(PickupPoint.builder()
                .operatorUser(cat.user("adama")).status(PartnerStatus.SUSPENDED)
                .name("Thiès Gare Routière Boutique")
                .addressStreet("Gare routière, Thiès")
                .city("Thiès").state("Thiès").countryCode("SN")
                .latitude(14.7880).longitude(-16.9250)
                .contactPhone("+221339500801")
                .managerName("Ibrahima Sow")
                .capacity(45).storageDays(5)
                .commissionPerParcel(SeedCatalogue.wholeUnits("500")).commissionCurrency("XOF")
                .active(false)
                .adminNote("Suspended: two parcels released without a code being presented.")
                .totalEarnings(SeedCatalogue.wholeUnits("9500"))
                .totalTransactions(19).monthlyDeliveries(0)
                .build()));

        cat.pickupPoints.put("farafenni", cat.save(PickupPoint.builder()
                .status(PartnerStatus.REJECTED)
                .name("Farafenni Highway Store")
                .addressStreet("Farafenni Highway")
                .city("Farafenni").state("North Bank").countryCode("GM")
                .contactPhone("+2204400805")
                .managerName("Alieu Ceesay")
                .capacity(30).storageDays(7)
                .commissionPerParcel(SeedCatalogue.money("30.00")).commissionCurrency("GMD")
                .active(false)
                .adminNote("Refused: no lockable storage on the premises.")
                .build()));
    }

    // ── Drivers ──────────────────────────────────────────────────────────────

    private void drivers(SeedCatalogue cat) {
        Driver ebrima = Driver.builder()
                .user(cat.user("ebrima")).status(DriverStatus.ACTIVE)
                .licenseNumber("GM-DL-2019-44213")
                .vehicleType(VehicleType.MOTOR).vehiclePlate("BJL 4421 G")
                .vehicleModel("Haojue HJ125").vehicleColor("Red")
                .phone("+2207300111")
                .avatarUrl("https://cdn.sujula.gm/sample/drivers/ebrima.jpg")
                .zone("Greater Banjul")
                .coverage(new LinkedHashSet<>(java.util.List.of(
                        cat.zone("banjul"), cat.zone("kanifing"))))
                .countryCode("GM")
                .currentLatitude(13.4460).currentLongitude(-16.6210)
                .lastLocationAt(cat.hoursAgo(1))
                .available(true).onlineSince(cat.hoursAgo(5))
                .idDocumentNumber("GM-ID-882104").idDocumentType("NATIONAL_ID")
                .idDocumentUrl("https://files.sujula.gm/sample/drivers/ebrima-id.pdf")
                .licenseDocumentUrl("https://files.sujula.gm/sample/drivers/ebrima-licence.pdf")
                .licenseExpiresOn(LocalDate.now().plusYears(2))
                .nextOfKinName("Awa Colley").nextOfKinPhone("+2207300112")
                .kycSubmittedAt(cat.daysAgo(200)).kycReviewedAt(cat.daysAgo(198))
                .acceptanceScore(SeedCatalogue.money("94.00"))
                .offersReceived(140).offersAccepted(132).offersDeclined(8)
                .commissionRate(SeedCatalogue.money("15.00"))
                .totalEarnings(SeedCatalogue.money("41250.00")).totalDeliveries(132)
                .averageRating(new BigDecimal("4.80")).totalRatings(97)
                .maxWeight(25)
                .build();
        cat.drivers.put("ebrima", cat.save(ebrima));

        // Approved and off shift. Approved but unavailable is the state the
        // assignment code must not offer work to.
        cat.drivers.put("saikou", cat.save(Driver.builder()
                .user(cat.user("saikou")).status(DriverStatus.APPROVED)
                .licenseNumber("GM-DL-2021-51120")
                .vehicleType(VehicleType.CAR).vehiclePlate("BJL 7710 G")
                .vehicleModel("Toyota Corolla").vehicleColor("Silver")
                .phone("+2207300222")
                .zone("West Coast")
                .coverage(new LinkedHashSet<>(java.util.List.of(
                        cat.zone("westcoast"), cat.zone("kanifing"))))
                .countryCode("GM")
                .currentLatitude(13.4200).currentLongitude(-16.7000)
                .lastLocationAt(cat.hoursAgo(14))
                .available(false)
                .idDocumentNumber("GM-ID-771320").idDocumentType("NATIONAL_ID")
                .licenseExpiresOn(LocalDate.now().plusYears(1))
                .nextOfKinName("Fatou Barrow").nextOfKinPhone("+2207300223")
                .kycSubmittedAt(cat.daysAgo(120)).kycReviewedAt(cat.daysAgo(118))
                .acceptanceScore(SeedCatalogue.money("78.00"))
                .offersReceived(50).offersAccepted(39).offersDeclined(11)
                .commissionRate(SeedCatalogue.money("15.00"))
                .totalEarnings(SeedCatalogue.money("12800.00")).totalDeliveries(39)
                .averageRating(new BigDecimal("4.30")).totalRatings(28)
                .maxWeight(60)
                .build()));

        cat.drivers.put("jainaba", cat.save(Driver.builder()
                .user(cat.user("jainaba")).status(DriverStatus.ACTIVE)
                .licenseNumber("GM-DL-2022-60441")
                .vehicleType(VehicleType.BICI).vehiclePlate("—")
                .vehicleModel("Cargo bicycle").vehicleColor("Green")
                .phone("+2207300555")
                .zone("Banjul island")
                .coverage(new LinkedHashSet<>(java.util.List.of(cat.zone("banjul"))))
                .countryCode("GM")
                .currentLatitude(13.4535).currentLongitude(-16.5780)
                .lastLocationAt(cat.hoursAgo(2))
                .available(true).onlineSince(cat.hoursAgo(3))
                .idDocumentNumber("GM-ID-903118").idDocumentType("NATIONAL_ID")
                .licenseExpiresOn(LocalDate.now().plusYears(3))
                .nextOfKinName("Musa Drammeh").nextOfKinPhone("+2207300556")
                .kycSubmittedAt(cat.daysAgo(80)).kycReviewedAt(cat.daysAgo(79))
                .acceptanceScore(SeedCatalogue.money("100.00"))
                .offersReceived(22).offersAccepted(22).offersDeclined(0)
                .commissionRate(SeedCatalogue.money("12.00"))
                .totalEarnings(SeedCatalogue.money("3960.00")).totalDeliveries(22)
                .averageRating(new BigDecimal("5.00")).totalRatings(19)
                // A bicycle. The weight ceiling is the whole reason this column
                // exists — a cast iron pot cannot go on it.
                .maxWeight(8)
                .build()));

        cat.drivers.put("aminata", cat.save(Driver.builder()
                .user(cat.user("aminata")).status(DriverStatus.SUSPENDED)
                .licenseNumber("SN-DL-2020-11902")
                .vehicleType(VehicleType.TAXI).vehiclePlate("DK 2290 A")
                .vehicleModel("Renault Logan").vehicleColor("Yellow")
                .phone("+221770777888")
                .zone("Dakar")
                .coverage(new LinkedHashSet<>(java.util.List.of(cat.zone("dakar"))))
                .countryCode("SN")
                .available(false)
                .idDocumentNumber("SN-ID-449021").idDocumentType("NATIONAL_ID")
                .licenseExpiresOn(LocalDate.now().plusMonths(8))
                .kycSubmittedAt(cat.daysAgo(150)).kycReviewedAt(cat.daysAgo(149))
                .adminNote("Suspended while a released-without-code complaint is looked at.")
                .acceptanceScore(SeedCatalogue.money("61.00"))
                .offersReceived(31).offersAccepted(19).offersDeclined(12)
                .commissionRate(SeedCatalogue.money("15.00"))
                .totalEarnings(SeedCatalogue.wholeUnits("142000")).totalDeliveries(19)
                .averageRating(new BigDecimal("3.60")).totalRatings(14)
                .maxWeight(40)
                .build()));

        // Applied, documents in, nobody has looked yet.
        cat.drivers.put("ousman", cat.save(Driver.builder()
                .user(cat.user("ousman")).status(DriverStatus.PENDING)
                .licenseNumber("GM-DL-2024-70228")
                .vehicleType(VehicleType.TRICYCLE).vehiclePlate("BJL 9012 G")
                .vehicleModel("Bajaj RE").vehicleColor("Blue")
                .phone("+2207300333")
                .countryCode("GM")
                .available(false)
                .idDocumentNumber("GM-ID-612009").idDocumentType("NATIONAL_ID")
                .idDocumentUrl("https://files.sujula.gm/sample/drivers/ousman-id.pdf")
                .licenseDocumentUrl("https://files.sujula.gm/sample/drivers/ousman-licence.pdf")
                .licenseExpiresOn(LocalDate.now().plusYears(4))
                .nextOfKinName("Binta Faal").nextOfKinPhone("+2207300334")
                .kycSubmittedAt(cat.daysAgo(3))
                .commissionRate(SeedCatalogue.money("15.00"))
                .maxWeight(150)
                .build()));

        cat.drivers.put("bakary", cat.save(Driver.builder()
                .user(cat.user("bakary")).status(DriverStatus.REJECTED)
                .licenseNumber("GM-DL-EXPIRED-1180")
                .vehicleType(VehicleType.CAR).vehiclePlate("BJL 1180 G")
                .vehicleModel("Mercedes 190").vehicleColor("White")
                .phone("+2207300444")
                .countryCode("GM")
                .available(false)
                .idDocumentNumber("GM-ID-118004").idDocumentType("NATIONAL_ID")
                .licenseExpiresOn(LocalDate.now().minusMonths(7))
                .kycSubmittedAt(cat.daysAgo(40)).kycReviewedAt(cat.daysAgo(39))
                .kycRejectionReason("Driving licence expired seven months ago.")
                .commissionRate(SeedCatalogue.money("15.00"))
                .maxWeight(60)
                .build()));
    }

    // ── Delivery contexts ────────────────────────────────────────────────────

    /**
     * Where the goods are going — held separately from who is paying.
     *
     * <p>Two of these belong to nobody. That is the point: a shopper with no
     * account still has a destination, and the catalogue still ranks against it.
     * One is expired, because a context is short-lived and code that forgets to
     * check gets a stale destination rather than an error.
     */
    private void deliveryContexts(SeedCatalogue cat) {
        // Isatou in Madrid, sending to Serrekunda. The delivery side says
        // Serrekunda; nothing here says Madrid, and nothing should.
        cat.save(DeliveryContext.builder()
                .id("dctx-sample-isatou-serrekunda-0001")
                .user(cat.user("isatou"))
                .latitude(13.4383).longitude(-16.6781)
                .addressLine("Sayerr Jobe Avenue, near Westfield")
                .city("Serrekunda").state("Kanifing").countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.USER_CONFIRMED)
                .mode(DeliveryMode.HOME_DELIVERY)
                .addressId(cat.address("isatou-serrekunda").getId())
                // The currency she is charged in, carried alongside — the
                // destination does not decide it.
                .currency("EUR").language("es").timezone("Europe/Madrid")
                .createdAt(cat.hoursAgo(2)).expiresAt(cat.daysAhead(29))
                .build());

        cat.save(DeliveryContext.builder()
                .id("dctx-sample-modou-brikama-0002")
                .user(cat.user("modou"))
                .latitude(13.2712).longitude(-16.6494)
                .addressLine("Brikama Nyambai Road")
                .city("Brikama").state("West Coast").countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.CENTROID)
                .mode(DeliveryMode.PICKUP_POINT)
                .pickupPointId(cat.pickupPoint("brikama").getId())
                .addressId(cat.address("modou-brikama").getId())
                .currency("GBP").language("en").timezone("Europe/London")
                .createdAt(cat.daysAgo(3)).expiresAt(cat.daysAhead(27))
                .build());

        cat.save(DeliveryContext.builder()
                .id("dctx-sample-binta-banjul-0003")
                .user(cat.user("binta"))
                .latitude(13.4549).longitude(-16.5790)
                .addressLine("12 Rene Blain Street")
                .city("Banjul").state("Banjul").countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.EXACT)
                .mode(DeliveryMode.HOME_DELIVERY)
                .addressId(cat.address("binta-banjul").getId())
                .currency("GMD").language("en").timezone("Africa/Banjul")
                .createdAt(cat.daysAgo(1)).expiresAt(cat.daysAhead(29))
                .build());

        cat.save(DeliveryContext.builder()
                .id("dctx-sample-cheikh-dakar-0004")
                .user(cat.user("cheikh"))
                .latitude(14.6937).longitude(-17.4441)
                .addressLine("Avenue Cheikh Anta Diop, Point E")
                .city("Dakar").state("Dakar").countryCode("SN")
                .geocodeConfidence(GeocodeConfidence.APPROXIMATE)
                .mode(DeliveryMode.VENDOR_PICKUP)
                .currency("XOF").language("fr").timezone("Africa/Dakar")
                .createdAt(cat.daysAgo(2)).expiresAt(cat.daysAhead(28))
                .build());

        // Nobody is signed in. Still a destination, still rankable.
        cat.save(DeliveryContext.builder()
                .id("dctx-sample-anonymous-serrekunda-0005")
                .latitude(13.4400).longitude(-16.6800)
                .addressLine("Serrekunda")
                .city("Serrekunda").state("Kanifing").countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.CENTROID)
                .mode(DeliveryMode.HOME_DELIVERY)
                .currency("GMD").language("en").timezone("Africa/Banjul")
                .createdAt(cat.hoursAgo(4)).expiresAt(cat.daysAhead(30))
                .build());

        // Anonymous and with no coordinates: a country was inferred from the IP
        // and nothing more. Everything downstream has to cope with that.
        cat.save(DeliveryContext.builder()
                .id("dctx-sample-anonymous-unlocated-0006")
                .countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.NONE)
                .mode(DeliveryMode.PICKUP_POINT)
                .pickupPointId(cat.pickupPoint("westfield").getId())
                .currency("GMD").language("en").timezone("Africa/Banjul")
                .createdAt(cat.hoursAgo(1)).expiresAt(cat.daysAhead(30))
                .build());

        // Already stale.
        cat.save(DeliveryContext.builder()
                .id("dctx-sample-expired-0007")
                .user(cat.user("sally"))
                .latitude(13.4383).longitude(-16.6781)
                .addressLine("Serrekunda")
                .city("Serrekunda").state("Kanifing").countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.CENTROID)
                .mode(DeliveryMode.HOME_DELIVERY)
                .currency("EUR").language("fr").timezone("Europe/Paris")
                .createdAt(cat.daysAgo(45)).expiresAt(cat.daysAgo(15))
                .build());

        // Unserviceable destination. Quotes against it must refuse with the
        // zone's own reason rather than a generic failure.
        cat.save(DeliveryContext.builder()
                .id("dctx-sample-northbank-0008")
                .user(cat.user("yankuba"))
                .latitude(13.4890).longitude(-16.0890)
                .addressLine("Kerewan")
                .city("Kerewan").state("North Bank").countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.CENTROID)
                .mode(DeliveryMode.HOME_DELIVERY)
                .currency("GMD").language("en").timezone("Africa/Banjul")
                .createdAt(cat.hoursAgo(6)).expiresAt(cat.daysAhead(30))
                .build());
    }

    // ── Routes ───────────────────────────────────────────────────────────────

    private void routes(SeedCatalogue cat) {
        cat.save(DeliveryRoute.builder()
                .driverId(cat.driver("ebrima").getId()).routeDate(LocalDate.now())
                .deliveryIds("1,2,3,4").optimizedOrder("3,1,4,2")
                .totalEstimatedDistanceKm(27.4).estimatedDurationMinutes(95)
                .build());
        cat.save(DeliveryRoute.builder()
                .driverId(cat.driver("ebrima").getId()).routeDate(LocalDate.now().minusDays(1))
                .deliveryIds("5,6").optimizedOrder("6,5")
                .totalEstimatedDistanceKm(11.9).estimatedDurationMinutes(40)
                .build());
        cat.save(DeliveryRoute.builder()
                .driverId(cat.driver("saikou").getId()).routeDate(LocalDate.now().minusDays(1))
                .deliveryIds("7,8,9").optimizedOrder("7,9,8")
                .totalEstimatedDistanceKm(48.2).estimatedDurationMinutes(140)
                .build());
        cat.save(DeliveryRoute.builder()
                .driverId(cat.driver("jainaba").getId()).routeDate(LocalDate.now())
                .deliveryIds("10").optimizedOrder("10")
                .totalEstimatedDistanceKm(3.1).estimatedDurationMinutes(18)
                .build());
        cat.save(DeliveryRoute.builder()
                .driverId(cat.driver("aminata").getId()).routeDate(LocalDate.now().minusDays(20))
                .deliveryIds("11,12").optimizedOrder("11,12")
                .totalEstimatedDistanceKm(19.7).estimatedDurationMinutes(66)
                .build());
        // Planned and empty: the driver is rostered and nothing is on the round
        // yet. A route page that assumes at least one stop meets this first.
        cat.save(DeliveryRoute.builder()
                .driverId(cat.driver("saikou").getId()).routeDate(LocalDate.now().plusDays(1))
                .deliveryIds("").optimizedOrder("")
                .totalEstimatedDistanceKm(0.0).estimatedDurationMinutes(0)
                .build());
    }
}
