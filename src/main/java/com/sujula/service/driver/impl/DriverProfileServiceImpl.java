package com.sujula.service.driver.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.driver.DriverRequests;
import com.sujula.dto.response.driver.DriverResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.LegAssignmentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.delivery.Driver;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.shipment.ShipmentLeg;
import com.sujula.model.user.User;
import com.sujula.repository.delivery.DriverRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.driver.DriverProfileService;
import com.sujula.service.shipment.CustodyChain;

import lombok.extern.slf4j.Slf4j;

/**
 * A driver's account and their queue.
 *
 * <p>Two things here are less obvious than they look.
 *
 * <p><strong>Location is refused while offline.</strong> Not throttled, refused.
 * A platform that accepted pings from drivers who had finished for the day would
 * be tracking people rather than parcels, and the difference is the whole of why
 * anybody would agree to carry a phone that reports where they are.
 *
 * <p><strong>An offer carries no address.</strong> The same leg goes to several
 * drivers and one of them takes it; a town and a distance are enough to decide,
 * and showing the destination would hand a recipient's home to every driver who
 * declined.
 */
@Slf4j
@Service
public class DriverProfileServiceImpl implements DriverProfileService {

    /**
     * The shortest gap between accepted pings.
     *
     * <p>Chosen for battery rather than for the server. A driver here may have
     * nowhere to charge between six in the morning and eight at night, and a
     * phone that died at eleven is a parcel nobody can find.
     */
    private static final Duration MIN_PING_INTERVAL = Duration.ofSeconds(20);

    /** How many legs a driver may hold at once, so dispatch stays fair. */
    private static final int MAX_CONCURRENT_LEGS = 8;

    private final DriverRepository drivers;
    private final UserRepository users;
    private final ShipmentLegRepository legs;
    private final ShipmentRepository shipments;
    private final CustodyChain custody;

    public DriverProfileServiceImpl(DriverRepository drivers, UserRepository users,
                                    ShipmentLegRepository legs, ShipmentRepository shipments,
                                    CustodyChain custody) {
        this.drivers = drivers;
        this.users = users;
        this.legs = legs;
        this.shipments = shipments;
        this.custody = custody;
    }

    // ── Becoming a driver ────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public DriverResponses.Profile apply(Long userId, DriverRequests.Apply request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (drivers.existsByUserId(userId)) {
            // Applying twice is a retried request, not a second application.
            return toProfile(drivers.findByUserId(userId).orElseThrow(),
                    "You have already applied. This is where your application has got to.");
        }

        LocalDateTime now = LocalDateTime.now();
        Driver driver = drivers.save(Driver.builder()
                .user(user)
                .status(DriverStatus.PENDING)
                .phone(request.phone())
                .vehicleType(request.vehicleType())
                .vehiclePlate(request.vehiclePlate())
                .vehicleModel(request.vehicleModel())
                .vehicleColor(request.vehicleColor())
                .licenseNumber(request.licenseNumber())
                .licenseExpiresOn(request.licenseExpiresOn())
                .idDocumentNumber(request.idDocumentNumber())
                .idDocumentType(request.idDocumentType())
                .idDocumentUrl(request.idDocumentUrl())
                .licenseDocumentUrl(request.licenseDocumentUrl())
                .nextOfKinName(request.nextOfKinName())
                .nextOfKinPhone(request.nextOfKinPhone())
                .zone(request.zone())
                .countryCode(request.countryCode() == null ? null
                        : request.countryCode().toUpperCase(java.util.Locale.ROOT))
                .maxWeight(request.maxWeightKg() == null ? 0 : request.maxWeightKg())
                .kycSubmittedAt(now)
                // Offline until approved and until they say otherwise. A new
                // application must not start receiving work by default.
                .available(false)
                .build());

        log.info("[Driver] User {} applied to drive in {} ({})",
                userId, request.zone(), request.countryCode());

        return toProfile(driver,
                "Application received. Somebody will check your documents before you can be "
                        + "offered work — you carry other people's goods, so this is not automatic.");
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.Profile myProfile(Long userId) {
        return toProfile(requireDriver(userId), null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.Profile updateProfile(Long userId, DriverRequests.UpdateProfile request) {
        Driver driver = requireDriver(userId);

        // Only what a driver may say about themselves. Nothing here touches
        // status, score or the documents somebody already reviewed - a driver
        // who could edit their own licence number after approval would make the
        // review meaningless.
        applyIfPresent(request.phone(), driver::setPhone);
        applyIfPresent(request.vehiclePlate(), driver::setVehiclePlate);
        applyIfPresent(request.vehicleModel(), driver::setVehicleModel);
        applyIfPresent(request.vehicleColor(), driver::setVehicleColor);
        applyIfPresent(request.zone(), driver::setZone);
        applyIfPresent(request.avatarUrl(), driver::setAvatarUrl);
        if (request.vehicleType() != null) {
            driver.setVehicleType(request.vehicleType());
        }
        if (request.countryCode() != null && !request.countryCode().isBlank()) {
            driver.setCountryCode(request.countryCode().toUpperCase(java.util.Locale.ROOT));
        }
        if (request.maxWeightKg() != null) {
            driver.setMaxWeight(request.maxWeightKg());
        }

        return toProfile(drivers.save(driver), "Saved.");
    }

    // ── Availability and position ────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.AvailabilitySet setAvailability(Long userId,
                                                           DriverRequests.SetAvailability request) {
        Driver driver = requireDriver(userId);
        boolean wantsOnline = request.online();

        if (wantsOnline && !driver.canCarry()) {
            // Said plainly rather than accepted and quietly ignored. A driver
            // who thinks they are online and is being offered nothing will
            // conclude the app is broken.
            throw new BadRequestException(switch (driver.getStatus()) {
                case PENDING -> "Your application has not been reviewed yet, so you cannot go "
                        + "online. You will be told when it has.";
                case REJECTED -> "This account cannot be used to carry goods.";
                case SUSPENDED -> "This account is suspended. Contact support before going online.";
                default -> "This account cannot go online.";
            });
        }

        LocalDateTime now = LocalDateTime.now();
        boolean wasOnline = driver.isAvailable();
        driver.setAvailable(wantsOnline);

        if (wantsOnline && !wasOnline) {
            driver.setOnlineSince(now);
        } else if (!wantsOnline) {
            driver.setOnlineSince(null);
            // The last known position is left alone rather than cleared: a
            // parcel this driver is still holding has to be findable, and an
            // offline driver with goods is exactly when that matters.
        }
        drivers.save(driver);

        log.info("[Driver] {} went {}", driver.getId(), wantsOnline ? "online" : "offline");

        return new DriverResponses.AvailabilitySet(wantsOnline, driver.getOnlineSince(),
                wantsOnline
                        ? "You are online and can be offered work."
                        : "You are offline. Anything you are already carrying is still yours to "
                                + "finish.");
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.LocationAccepted ping(Long userId, DriverRequests.Ping request) {
        Driver driver = requireDriver(userId);

        if (!driver.isAvailable()) {
            throw new BadRequestException(
                    "You are offline, so your position is not being recorded. Go online first.");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = driver.getLastLocationAt();
        if (last != null) {
            long since = Duration.between(last, now).toSeconds();
            if (since < MIN_PING_INTERVAL.toSeconds()) {
                // Answered rather than refused, and told when to come back. An
                // error here would have the app retry immediately, which is the
                // opposite of what throttling is for.
                return new DriverResponses.LocationAccepted(last, true,
                        MIN_PING_INTERVAL.toSeconds() - since,
                        "Position not updated — you sent one " + since + " seconds ago. Sending "
                                + "less often saves the battery you need for the rest of the day.");
            }
        }

        driver.setCurrentLatitude(request.lat());
        driver.setCurrentLongitude(request.lng());
        driver.setLastLocationAt(now);
        drivers.save(driver);

        return new DriverResponses.LocationAccepted(now, false,
                MIN_PING_INTERVAL.toSeconds(), null);
    }

    // ── Assignments ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.Assignments assignments(Long userId) {
        Driver driver = requireDriver(userId);
        LocalDateTime now = LocalDateTime.now();

        List<DriverResponses.Assignment> offered = new ArrayList<>();
        List<DriverResponses.Assignment> accepted = new ArrayList<>();

        for (ShipmentLeg leg : legs.findLiveForDriver(driver.getId(), now)) {
            DriverResponses.Assignment row = toAssignment(leg, now);
            if (leg.getAssignmentStatus() == LegAssignmentStatus.OFFERED) {
                offered.add(row);
            } else {
                accepted.add(row);
            }
        }

        return new DriverResponses.Assignments(offered, accepted,
                offered.isEmpty() && accepted.isEmpty()
                        ? (driver.isAvailable()
                            ? "Nothing for you right now."
                            : "You are offline, so nothing is being offered to you.")
                        : null);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.AssignmentAnswered accept(Long userId, Long legId) {
        Driver driver = requireDriver(userId);
        ShipmentLeg leg = requireOwnLeg(legId, driver);
        LocalDateTime now = LocalDateTime.now();

        if (leg.getAssignmentStatus() == LegAssignmentStatus.ACCEPTED
                || leg.getAssignmentStatus() == LegAssignmentStatus.IN_PROGRESS) {
            // A retried tap on a bad connection.
            return new DriverResponses.AssignmentAnswered(legId, leg.getAssignmentStatus(),
                    leg.getAcceptedAt(), toScore(driver), "You already have this one.");
        }
        if (!leg.getAssignmentStatus().isAnswerable()) {
            throw new BadRequestException(
                    "This job is no longer yours to answer — it is " + leg.getAssignmentStatus() + ".");
        }
        if (leg.getOfferExpiresAt() != null && !leg.getOfferExpiresAt().isAfter(now)) {
            leg.setAssignmentStatus(LegAssignmentStatus.EXPIRED);
            legs.save(leg);
            throw new BadRequestException(
                    "That offer has lapsed and has gone back into the pool. It is not held against "
                            + "you.");
        }
        if (!driver.canCarry()) {
            throw new BadRequestException("This account cannot take work at the moment.");
        }

        long holding = legs.countByDriverIdAndAssignmentStatusIn(driver.getId(),
                List.of(LegAssignmentStatus.ACCEPTED, LegAssignmentStatus.IN_PROGRESS));
        if (holding >= MAX_CONCURRENT_LEGS) {
            throw new BadRequestException(
                    "You already have " + holding + " jobs open. Finish some before taking more — "
                            + "a parcel accepted and not moved is worse for the person waiting than "
                            + "one offered to somebody else.");
        }

        leg.setAssignmentStatus(LegAssignmentStatus.ACCEPTED);
        leg.setAcceptedAt(now);
        legs.save(leg);

        driver.recordOffer(true);
        drivers.save(driver);

        // The shipment's status follows from its legs when no event has happened
        // yet, so it is re-derived rather than set.
        Shipment shipment = leg.getShipment();
        custody.rederive(shipment, List.of());

        log.info("[Driver] {} accepted leg {} of shipment {}",
                driver.getId(), legId, shipment.getReference());

        return new DriverResponses.AssignmentAnswered(legId, leg.getAssignmentStatus(), now,
                toScore(driver), "Accepted. Head to the pickup and mark yourself arrived.");
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.AssignmentAnswered decline(Long userId, Long legId,
                                                      DriverRequests.Decline request) {
        Driver driver = requireDriver(userId);
        ShipmentLeg leg = requireOwnLeg(legId, driver);

        if (leg.getAssignmentStatus() == LegAssignmentStatus.DECLINED) {
            return new DriverResponses.AssignmentAnswered(legId, leg.getAssignmentStatus(),
                    leg.getDeclinedAt(), toScore(driver), "You had already turned this one down.");
        }
        if (!leg.getAssignmentStatus().isAnswerable()) {
            throw new BadRequestException(
                    "This job is no longer yours to answer — it is " + leg.getAssignmentStatus() + ".");
        }

        LocalDateTime now = LocalDateTime.now();
        leg.setAssignmentStatus(LegAssignmentStatus.DECLINED);
        leg.setDeclinedAt(now);
        leg.setDeclineReason(request.reason());
        // Back into the pool: the driver is cleared off it so it can be offered
        // to somebody else without carrying who said no.
        leg.setDriver(null);
        legs.save(leg);

        driver.recordOffer(false);
        drivers.save(driver);

        custody.rederive(leg.getShipment(), List.of());

        log.info("[Driver] {} declined leg {} — {}", driver.getId(), legId, request.reason());

        return new DriverResponses.AssignmentAnswered(legId, LegAssignmentStatus.DECLINED, now,
                toScore(driver),
                "Turned down. It goes back to dispatch. Declining is fine; declining everything "
                        + "means you are offered less.");
    }

    // ── Money and history ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.Earnings earnings(Long userId, LocalDate from, LocalDate to) {
        Driver driver = requireDriver(userId);
        LocalDate end = to != null ? to : LocalDate.now().plusDays(1);
        LocalDate start = from != null ? from : end.minusDays(30);
        if (!start.isBefore(end)) {
            throw new BadRequestException("The start of the period must come before the end.");
        }

        Map<String, List<DriverResponses.EarningLine>> byCurrency = new LinkedHashMap<>();
        int deliveries = 0;

        for (ShipmentLeg leg : legs.findAll()) {
            if (leg.getDriver() == null || !driver.getId().equals(leg.getDriver().getId())) {
                continue;
            }
            if (leg.getAssignmentStatus() != LegAssignmentStatus.COMPLETED
                    || leg.getCompletedAt() == null) {
                continue;
            }
            LocalDate day = leg.getCompletedAt().toLocalDate();
            if (day.isBefore(start) || !day.isBefore(end)) {
                continue;
            }
            // A leg with no fee agreed is still a leg they did; it is counted
            // and contributes nothing, rather than being hidden.
            String currency = leg.getEarningCurrency() == null ? "GMD" : leg.getEarningCurrency();
            byCurrency.computeIfAbsent(currency, key -> new ArrayList<>())
                    .add(new DriverResponses.EarningLine(
                            leg.getId(), leg.getShipment().getId(),
                            leg.getShipment().getReference(), leg.getLegType(),
                            leg.getEarning() == null ? BigDecimal.ZERO : leg.getEarning(),
                            currency, leg.getCompletedAt(),
                            leg.getShipment().getDestinationCity()));
            deliveries++;
        }

        List<DriverResponses.CurrencyEarnings> summaries = new ArrayList<>();
        for (Map.Entry<String, List<DriverResponses.EarningLine>> entry : byCurrency.entrySet()) {
            BigDecimal total = entry.getValue().stream()
                    .map(DriverResponses.EarningLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            summaries.add(new DriverResponses.CurrencyEarnings(entry.getKey(), total,
                    entry.getValue().size(),
                    entry.getValue().isEmpty() ? BigDecimal.ZERO
                            : total.divide(BigDecimal.valueOf(entry.getValue().size()), 2,
                                    RoundingMode.HALF_UP),
                    entry.getValue()));
        }

        return new DriverResponses.Earnings(start, end, summaries, deliveries,
                summaries.size() > 1
                        ? "You have worked legs paid in more than one currency. They are kept "
                          + "apart rather than added: no single rate is true of all of them."
                        : null);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DELIVERY') and #userId == authentication.principal.id)")
    public DriverResponses.History history(Long userId, Pageable pageable) {
        Driver driver = requireDriver(userId);
        Page<Shipment> page = shipments.findHistoryForDriver(driver.getId(), pageable);

        List<DriverResponses.HistoryRow> rows = new ArrayList<>();
        for (Shipment shipment : page.getContent()) {
            ShipmentLeg mine = shipment.getLegs().stream()
                    .filter(l -> l.getDriver() != null && driver.getId().equals(l.getDriver().getId()))
                    .findFirst().orElse(null);

            rows.add(new DriverResponses.HistoryRow(
                    shipment.getId(), shipment.getReference(), shipment.getStatus(),
                    mine == null ? null : mine.getLegType(),
                    // A town, not an address. A round from three months ago is
                    // not a reason to still hold somebody's front door.
                    shipment.getDestinationCity(), shipment.getDestinationCountry(),
                    mine == null ? null : mine.getEarning(),
                    mine == null ? null : mine.getEarningCurrency(),
                    shipment.getCollectedAt(),
                    mine == null ? null : mine.getCompletedAt()));
        }

        return new DriverResponses.History(rows, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private DriverResponses.Assignment toAssignment(ShipmentLeg leg, LocalDateTime now) {
        Shipment shipment = leg.getShipment();
        Long secondsLeft = leg.getOfferExpiresAt() == null ? null
                : Math.max(0, ChronoUnit.SECONDS.between(now, leg.getOfferExpiresAt()));

        return new DriverResponses.Assignment(
                leg.getId(), shipment.getId(), shipment.getReference(),
                leg.getLegType(), leg.getAssignmentStatus(), leg.getSequence(),
                // Labels, not addresses. Enough to decide whether the job is
                // worth taking; nothing a driver who declines should keep.
                leg.getOriginLabel() != null ? leg.getOriginLabel() : shipment.getOriginAddress(),
                leg.getDestinationLabel() != null ? leg.getDestinationLabel()
                        : shipment.getDestinationCity(),
                leg.getDistanceKm(), leg.getEarning(), leg.getEarningCurrency(),
                shipment.getParcelCount() == null ? 1 : shipment.getParcelCount(),
                leg.getOfferedAt(), leg.getOfferExpiresAt(), secondsLeft,
                leg.getAssignmentStatus() == LegAssignmentStatus.OFFERED && secondsLeft != null
                        ? "Answer within " + secondsLeft + " seconds or it goes to somebody else."
                        : null);
    }

    private static DriverResponses.Score toScore(Driver driver) {
        return new DriverResponses.Score(driver.getAcceptanceScore(),
                driver.getOffersReceived(), driver.getOffersAccepted(), driver.getOffersDeclined(),
                "How often you take what you are offered. It affects how much you are offered.");
    }

    private DriverResponses.Profile toProfile(Driver driver, String message) {
        return new DriverResponses.Profile(
                driver.getId(), driver.getStatus(), driver.isAvailable(),
                driver.getPhone(), driver.getVehicleType(), driver.getVehiclePlate(),
                driver.getVehicleModel(), driver.getVehicleColor(), driver.getAvatarUrl(),
                driver.getZone(), driver.getCountryCode(), driver.getMaxWeight(),
                driver.getLicenseNumber(), driver.getLicenseExpiresOn(),
                new DriverResponses.Kyc(
                        driver.getIdDocumentUrl() != null || driver.getLicenseDocumentUrl() != null,
                        driver.getKycSubmittedAt(), driver.getKycReviewedAt(),
                        driver.getKycRejectionReason(),
                        whatIsNeeded(driver)),
                toScore(driver),
                driver.getTotalDeliveries(), driver.getAverageRating(),
                driver.getOnlineSince(), driver.getLastLocationAt(),
                driver.getCreatedAt(), message);
    }

    /** Says what is missing rather than merely that something is. */
    private static String whatIsNeeded(Driver driver) {
        if (driver.getStatus() == DriverStatus.REJECTED) {
            return driver.getKycRejectionReason() == null
                    ? "This application was not accepted."
                    : driver.getKycRejectionReason();
        }
        List<String> missing = new ArrayList<>();
        if (driver.getIdDocumentUrl() == null) {
            missing.add("a photograph of your identity document");
        }
        if (driver.getLicenseDocumentUrl() == null) {
            missing.add("a photograph of your driving licence");
        }
        if (driver.getNextOfKinPhone() == null || driver.getNextOfKinPhone().isBlank()) {
            missing.add("a next of kin we can reach");
        }
        if (!missing.isEmpty()) {
            return "Still needed: " + String.join(", ", missing) + ".";
        }
        return driver.getStatus() == DriverStatus.PENDING
                ? "Everything is in. Waiting for somebody to review it."
                : null;
    }

    private static void applyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value.trim());
        }
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    private Driver requireDriver(Long userId) {
        return drivers.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "You do not have a driver profile. Apply first."));
    }

    /**
     * The driver id goes into the query, so another driver's leg comes back
     * empty rather than being fetched and refused. Not found rather than
     * forbidden: this row leads to a recipient's address.
     */
    private ShipmentLeg requireOwnLeg(Long legId, Driver driver) {
        return legs.findByIdAndDriverId(legId, driver.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", legId));
    }
}
