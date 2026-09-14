package com.sujula.service.admin.impl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.admin.AdminLogisticsRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminLogisticsResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.delivery.Driver;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.logistics.DeliveryRateCard;
import com.sujula.model.logistics.DeliveryZone;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.user.User;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.DriverRepository;
import com.sujula.repository.logistics.DeliveryRateCardRepository;
import com.sujula.repository.logistics.DeliveryZoneRepository;
import com.sujula.repository.shipment.ShipmentLegRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.AuditService;
import com.sujula.service.NotificationService;
import com.sujula.service.admin.AdminLogisticsService;
import com.sujula.service.delivery.CountryBounds;
import com.sujula.service.delivery.GeoJsonPolygon;
import com.sujula.service.delivery.LegRate;
import com.sujula.service.delivery.RateCardRegistry;
import com.sujula.service.delivery.ZoneRegistry;
import com.sujula.service.pickup.PickupCounter;

import lombok.extern.slf4j.Slf4j;

/**
 * Drivers, counters, zones and rate cards.
 *
 * <p>Four write paths, and the same discipline on all of them: a decision that
 * affects somebody is told to them and written to the audit log, and a decision
 * that affects a price takes effect from a date rather than retroactively.
 *
 * <p>Two things here are worth reading before changing them. A rate card is
 * never edited in its numbers — a new card from a new day supersedes it, and the
 * old one is closed rather than overwritten, because the old figures are what
 * orders were priced under and nobody can explain a total whose inputs were
 * changed afterwards. And a zone write always ends by invalidating the
 * serviceability cache, because the reason somebody edits a zone is almost
 * always that parcels are being quoted wrong right now.
 */
@Slf4j
@Service
public class AdminLogisticsServiceImpl implements AdminLogisticsService {

    /** Legs the rate preview prices, so a new card can be sanity-checked. */
    private static final BigDecimal[][] PREVIEW_LEGS = {
            { new BigDecimal("5"),   new BigDecimal("1")  },
            { new BigDecimal("20"),  new BigDecimal("3")  },
            { new BigDecimal("100"), new BigDecimal("10") }
    };

    private final DriverRepository drivers;
    private final PickupPointRepository pickupPoints;
    private final DeliveryZoneRepository zones;
    private final DeliveryRateCardRepository rateCards;
    private final ShipmentRepository shipments;
    private final ShipmentLegRepository legs;
    private final UserRepository users;
    private final ZoneRegistry zoneRegistry;
    private final RateCardRegistry rateCardRegistry;
    private final PickupCounter counter;
    private final AuditService audit;
    private final NotificationService notifications;

    public AdminLogisticsServiceImpl(DriverRepository drivers, PickupPointRepository pickupPoints,
                                     DeliveryZoneRepository zones,
                                     DeliveryRateCardRepository rateCards,
                                     ShipmentRepository shipments, ShipmentLegRepository legs,
                                     UserRepository users, ZoneRegistry zoneRegistry,
                                     RateCardRegistry rateCardRegistry, PickupCounter counter,
                                     AuditService audit, NotificationService notifications) {
        this.drivers = drivers;
        this.pickupPoints = pickupPoints;
        this.zones = zones;
        this.rateCards = rateCards;
        this.shipments = shipments;
        this.legs = legs;
        this.users = users;
        this.zoneRegistry = zoneRegistry;
        this.rateCardRegistry = rateCardRegistry;
        this.counter = counter;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ── Drivers ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminLogisticsResponses.DriverRow> listDrivers(
            String q, DriverStatus status, String countryCode, String zoneCode,
            Boolean availableOnly, Pageable pageable) {

        Page<Driver> page = drivers.adminSearch(blankToNull(q), status, blankToNull(countryCode),
                blankToNull(zoneCode), Boolean.TRUE.equals(availableOnly), pageable);
        return PagedResponse.of(page.map(this::toDriverRow));
    }

    private AdminLogisticsResponses.DriverRow toDriverRow(Driver driver) {
        User user = driver.getUser();
        LocalDateTime now = LocalDateTime.now();

        Long staleMinutes = driver.getLastLocationAt() == null ? null
                : Duration.between(driver.getLastLocationAt(), now).toMinutes();

        List<String> flags = new ArrayList<>();
        if (driver.getKycSubmittedAt() != null && driver.getKycReviewedAt() == null) {
            flags.add("KYC waiting since " + driver.getKycSubmittedAt().toLocalDate());
        }
        if (driver.getKycSubmittedAt() == null && driver.getStatus() == DriverStatus.PENDING) {
            flags.add("No KYC submitted");
        }
        if (driver.getLicenseExpiresOn() != null) {
            if (driver.getLicenseExpiresOn().isBefore(LocalDate.now())) {
                flags.add("Licence expired " + driver.getLicenseExpiresOn());
            } else if (driver.getLicenseExpiresOn().isBefore(LocalDate.now().plusDays(30))) {
                flags.add("Licence expires " + driver.getLicenseExpiresOn());
            }
        }
        // Online with a stale position is the one combination worth flagging: it
        // reads as an unreliable driver and is nearly always a dead phone, and
        // the parcel they are holding is somebody's.
        if (driver.isAvailable() && staleMinutes != null && staleMinutes > 120) {
            flags.add("On shift but no position for " + staleMinutes + " minutes");
        }
        if (driver.isAvailable() && driver.getLastLocationAt() == null) {
            flags.add("On shift and has never reported a position");
        }
        if (driver.canCarry() && driver.getCoverage().isEmpty()) {
            flags.add("Approved but covers no zone — will not be offered work");
        }

        return new AdminLogisticsResponses.DriverRow(
                driver.getId(), user == null ? null : user.getId(),
                nameOf(user), user == null ? null : user.getEmail(), driver.getPhone(),
                driver.getStatus(), driver.getCountryCode(), driver.getZone(),
                badges(driver.getCoverage()),
                driver.isAvailable(), driver.getOnlineSince(), driver.getLastLocationAt(),
                staleMinutes,
                driver.getAcceptanceScore(), driver.getOffersReceived(),
                driver.getOffersAccepted(), driver.getOffersDeclined(),
                legs.countOpenJobs(driver.getId()),
                driver.getTotalDeliveries(), driver.getAverageRating(), driver.getTotalRatings(),
                flags, driver.getAdminNote());
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.DriverDecision approveDriver(
            User staff, Long driverId, AdminLogisticsRequests.ApproveDriver request) {

        Driver driver = requireDriver(driverId);
        if (driver.getStatus() == DriverStatus.APPROVED || driver.getStatus() == DriverStatus.ACTIVE) {
            return new AdminLogisticsResponses.DriverDecision(driver.getId(), nameOf(driver.getUser()),
                    driver.getStatus(), badges(driver.getCoverage()),
                    "Already approved — nothing changed.");
        }

        // A driver holds other people's goods and meets families at their homes.
        // Approving one who submitted nothing is approving a name.
        if (driver.getIdDocumentUrl() == null || driver.getIdDocumentUrl().isBlank()) {
            throw new BadRequestException(
                    "This driver has not submitted an identity document. Approving them would put "
                            + "somebody's phone in the hands of a name with nothing behind it.");
        }
        if (driver.getLicenseExpiresOn() != null
                && driver.getLicenseExpiresOn().isBefore(LocalDate.now())) {
            throw new BadRequestException(
                    "Their licence expired on " + driver.getLicenseExpiresOn()
                            + ". Ask for a current one before approving.");
        }

        driver.setStatus(DriverStatus.APPROVED);
        driver.setKycReviewedAt(LocalDateTime.now());
        driver.setKycRejectionReason(null);
        if (request.note() != null && !request.note().isBlank()) {
            driver.setAdminNote(request.note());
        }
        if (request.zoneCodes() != null && !request.zoneCodes().isEmpty()) {
            driver.setCoverage(resolveZones(request.zoneCodes()));
        }
        drivers.save(driver);

        audit.record(AuditAction.DRIVER_APPROVED, "DRIVER", driver.getId(), nameOf(driver.getUser()),
                staff.getEmail() + " approved the driver", request.note());

        notifyDriver(driver, "You can start taking deliveries",
                "Your application has been approved."
                        + (driver.getCoverage().isEmpty()
                                ? " Zones are still being set up, so offers will start shortly."
                                : " You will be offered work in "
                                        + zoneNames(driver.getCoverage()) + "."));

        return new AdminLogisticsResponses.DriverDecision(driver.getId(), nameOf(driver.getUser()),
                driver.getStatus(), badges(driver.getCoverage()),
                driver.getCoverage().isEmpty()
                        ? "Approved. They cover no zone yet, so nothing will be offered to them "
                          + "until you set one."
                        : "Approved for " + zoneNames(driver.getCoverage()) + ".");
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.DriverDecision suspendDriver(
            User staff, Long driverId, AdminLogisticsRequests.SuspendDriver request) {

        Driver driver = requireDriver(driverId);

        // Their open jobs are named rather than cancelled here. A suspension is a
        // decision about the person; the parcels they are carrying are somebody
        // else's property and have to be moved deliberately, by somebody looking
        // at each one.
        int open = legs.countOpenJobs(driver.getId());

        driver.setStatus(DriverStatus.SUSPENDED);
        driver.setAvailable(false);
        driver.setOnlineSince(null);
        driver.setAdminNote(request.reason());
        drivers.save(driver);

        audit.record(AuditAction.DRIVER_SUSPENDED, "DRIVER", driver.getId(), nameOf(driver.getUser()),
                staff.getEmail() + " suspended the driver"
                        + (request.until() == null ? "" : " until " + request.until())
                        + (open == 0 ? "" : " (" + open + " job(s) still open)"),
                request.reason());

        notifyDriver(driver, "Your account has been suspended",
                request.reason()
                        + (request.until() == null ? ""
                                : " This lasts until " + request.until().toLocalDate() + ".")
                        + (open == 0 ? ""
                                : " You are still holding " + open + " parcel(s) — dispatch will "
                                  + "contact you to arrange handing them over."));

        return new AdminLogisticsResponses.DriverDecision(driver.getId(), nameOf(driver.getUser()),
                driver.getStatus(), badges(driver.getCoverage()),
                open == 0
                        ? "Suspended. They were carrying nothing."
                        : "Suspended, but they are still holding " + open + " parcel(s). Those are "
                          + "not cancelled — move each one with a transfer or a reassignment so the "
                          + "custody chain stays whole.");
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.DriverDecision setDriverZones(
            User staff, Long driverId, AdminLogisticsRequests.SetDriverZones request) {

        Driver driver = requireDriver(driverId);
        Set<DeliveryZone> resolved = resolveZones(request.zoneCodes());

        // A driver approved in one country and given a zone in another is a
        // dispatch bug waiting to be filed as a missing parcel.
        if (driver.getCountryCode() != null) {
            for (DeliveryZone zone : resolved) {
                if (!driver.getCountryCode().equalsIgnoreCase(zone.getCountryCode())) {
                    throw new BadRequestException(
                            "Zone " + zone.getCode() + " is in " + zone.getCountryCode()
                                    + " and this driver works in " + driver.getCountryCode()
                                    + ". A driver cannot cover a zone in a country they are not in.");
                }
            }
        }

        String before = driver.getCoverage().isEmpty() ? "nothing" : zoneNames(driver.getCoverage());
        driver.setCoverage(resolved);
        drivers.save(driver);

        audit.record(AuditAction.DRIVER_ZONES_CHANGED, "DRIVER", driver.getId(),
                nameOf(driver.getUser()),
                staff.getEmail() + " changed coverage from " + before + " to "
                        + (resolved.isEmpty() ? "nothing" : zoneNames(resolved)),
                request.note());

        notifyDriver(driver,
                resolved.isEmpty() ? "Your delivery areas have been cleared"
                                   : "Your delivery areas have changed",
                resolved.isEmpty()
                        ? "You will not be offered new work until an area is set again."
                        : "You will now be offered work in " + zoneNames(resolved) + ".");

        return new AdminLogisticsResponses.DriverDecision(driver.getId(), nameOf(driver.getUser()),
                driver.getStatus(), badges(resolved),
                resolved.isEmpty()
                        ? "Coverage cleared. Nothing will be offered to them until you set a zone."
                        : "Now covering " + zoneNames(resolved) + ".");
    }

    // ── Pickup points ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminLogisticsResponses.PickupPointRow> listPickupPoints(
            String q, PartnerStatus status, String countryCode, Boolean overdueOnly,
            Boolean fullOnly, Pageable pageable) {

        Page<PickupPoint> page = pickupPoints.adminSearch(blankToNull(q), status,
                blankToNull(countryCode), Boolean.TRUE.equals(fullOnly), pageable);

        List<AdminLogisticsResponses.PickupPointRow> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (PickupPoint point : page.getContent()) {
            AdminLogisticsResponses.PickupPointRow row = toPickupRow(point, now);
            if (Boolean.TRUE.equals(overdueOnly) && row.overdueParcels() == 0) {
                continue;
            }
            rows.add(row);
        }

        // Filtered after the page rather than inside the query, because "overdue"
        // is a fact about the parcels on a shelf rather than about the point, and
        // the totals stay honest about the page that was actually read.
        return PagedResponse.<AdminLogisticsResponses.PickupPointRow>builder()
                .content(rows)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    private AdminLogisticsResponses.PickupPointRow toPickupRow(PickupPoint point,
                                                               LocalDateTime now) {
        List<Shipment> overdue = shipments.findOverdueAt(point.getId(), now);
        int capacity = point.getCapacity() == null ? 0 : point.getCapacity();
        int stored = point.getStoredParcels() == null ? 0 : point.getStoredParcels();

        List<String> flags = new ArrayList<>();
        if (!point.hasSpace()) {
            flags.add("Full — new parcels are being refused");
        } else if (capacity > 0 && stored >= capacity * 0.9) {
            flags.add("Nearly full");
        }
        if (!overdue.isEmpty()) {
            flags.add(overdue.size() + " parcel(s) past their storage deadline");
        }
        if (point.isTemporarilyClosed()) {
            flags.add("Closed until " + point.getClosedUntil());
        }
        if (point.getOperatorUser() == null) {
            flags.add("No operator — the platform is running this one");
        }
        if (point.getLatitude() == null || point.getLongitude() == null) {
            flags.add("No pin — a driver cannot be routed here");
        }

        return new AdminLogisticsResponses.PickupPointRow(
                point.getId(), point.getName(), point.getStatus(), point.isActive(),
                point.getCity(), point.getCountryCode(), point.getLatitude(), point.getLongitude(),
                point.getOperatorUser() == null ? null : nameOf(point.getOperatorUser()),
                point.getOperatorUser() == null ? null : point.getOperatorUser().getId(),
                point.getCapacity(), point.getStoredParcels(),
                Math.max(0, capacity - stored),
                overdue.size(),
                overdue.isEmpty() ? null : overdue.get(0).getStorageDeadline(),
                point.getStorageDays(), point.getCommissionPerParcel(),
                point.getCommissionCurrency(),
                point.getClosedUntil(), point.getClosureReason(),
                flags, point.getAdminNote());
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.PickupPointSaved createPickupPoint(
            User staff, AdminLogisticsRequests.CreatePickupPoint request) {

        if (pickupPoints.nameTakenInCity(request.name(), request.city(),
                request.countryCode(), null)) {
            throw new BadRequestException(
                    "There is already a point called \"" + request.name() + "\" in "
                            + request.city() + ". Two counters with one name is how a driver "
                            + "delivers to the wrong shop.");
        }

        User operator = null;
        if (request.operatorUserId() != null) {
            operator = users.findById(request.operatorUserId()).orElseThrow(
                    () -> new ResourceNotFoundException("No such user to run this point."));
        }

        PickupPoint point = PickupPoint.builder()
                .name(request.name().trim())
                .addressStreet(request.addressStreet().trim())
                .addressApartment(request.addressApartment())
                .city(request.city().trim())
                .state(request.state())
                .postalCode(request.postalCode())
                .countryCode(request.countryCode().toUpperCase())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .contactPhone(request.contactPhone())
                .contactEmail(request.contactEmail())
                .managerName(request.managerName())
                .openingHours(request.openingHours())
                .capacity(request.capacity() == null ? 50 : request.capacity())
                .storageDays(request.storageDays() == null ? 7 : request.storageDays())
                .commissionPerParcel(request.commissionPerParcel() == null
                        ? BigDecimal.ZERO : request.commissionPerParcel())
                .commissionCurrency(request.commissionCurrency() == null
                        ? "GMD" : request.commissionCurrency().toUpperCase())
                .operatorUser(operator)
                .adminNote(request.adminNote())
                // Created by an administrator, so approved on creation: the
                // approval step exists to review an application from outside,
                // and there is no application here.
                .status(PartnerStatus.APPROVED)
                .active(true)
                .build();
        pickupPoints.save(point);

        audit.record(AuditAction.PICKUP_POINT_CREATED_BY_ADMIN, "PICKUP_POINT", point.getId(),
                point.getName(),
                staff.getEmail() + " created the point in " + point.getCity() + ", "
                        + point.getCountryCode(),
                request.adminNote());

        if (operator != null) {
            notifications.send(operator.getId(), "You have been given a collection point",
                    point.getName() + " in " + point.getCity() + " is now yours to run. "
                            + "Parcels will start arriving once drivers are routed there.",
                    NotificationEvent.PICKUP_PARCEL_ARRIVED, String.valueOf(point.getId()));
        }

        return new AdminLogisticsResponses.PickupPointSaved(point.getId(), point.getName(),
                point.getStatus(), point.isActive(),
                "Created and open. It holds " + point.getCapacity() + " parcels and keeps them "
                        + point.getStorageDays() + " days.");
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.PickupPointSaved patchPickupPoint(
            User staff, Long id, AdminLogisticsRequests.PatchPickupPoint request) {

        PickupPoint point = requirePoint(id);
        List<String> changes = new ArrayList<>();

        if (request.name() != null && !request.name().equals(point.getName())) {
            if (pickupPoints.nameTakenInCity(request.name(),
                    request.city() != null ? request.city() : point.getCity(),
                    point.getCountryCode(), point.getId())) {
                throw new BadRequestException(
                        "Another point in that city is already called \"" + request.name() + "\".");
            }
            changes.add("name");
            point.setName(request.name().trim());
        }
        if (request.addressStreet() != null) { point.setAddressStreet(request.addressStreet()); changes.add("street"); }
        if (request.addressApartment() != null) point.setAddressApartment(request.addressApartment());
        if (request.city() != null) { point.setCity(request.city()); changes.add("city"); }
        if (request.state() != null) point.setState(request.state());
        if (request.postalCode() != null) point.setPostalCode(request.postalCode());
        if (request.latitude() != null) { point.setLatitude(request.latitude()); changes.add("pin"); }
        if (request.longitude() != null) { point.setLongitude(request.longitude()); changes.add("pin"); }
        if (request.contactPhone() != null) point.setContactPhone(request.contactPhone());
        if (request.contactEmail() != null) point.setContactEmail(request.contactEmail());
        if (request.managerName() != null) point.setManagerName(request.managerName());
        if (request.openingHours() != null) point.setOpeningHours(request.openingHours());
        if (request.storageDays() != null) { point.setStorageDays(request.storageDays()); changes.add("storage days"); }
        if (request.commissionPerParcel() != null) {
            point.setCommissionPerParcel(request.commissionPerParcel());
            changes.add("commission");
        }
        if (request.commissionCurrency() != null) {
            point.setCommissionCurrency(request.commissionCurrency().toUpperCase());
        }
        if (request.adminNote() != null) point.setAdminNote(request.adminNote());

        if (request.capacity() != null) {
            int onShelf = counter.recount(point);
            if (request.capacity() < onShelf) {
                throw new BadRequestException(
                        "There are " + onShelf + " parcels on that shelf right now and you are "
                                + "setting capacity to " + request.capacity() + ". Lower it once "
                                + "they have been collected — a capacity below what is already "
                                + "stored says the point is over-full when nobody did anything "
                                + "wrong.");
            }
            point.setCapacity(request.capacity());
            changes.add("capacity");
        }

        if (request.operatorUserId() != null) {
            User operator = users.findById(request.operatorUserId()).orElseThrow(
                    () -> new ResourceNotFoundException("No such user to run this point."));
            point.setOperatorUser(operator);
            changes.add("operator");
        }

        if (request.active() != null && request.active() != point.isActive()) {
            if (!request.active()) {
                requireShelfEmptyToClose(point, "switched off");
            }
            point.setActive(request.active());
            changes.add(request.active() ? "switched on" : "switched off");
        }

        pickupPoints.save(point);

        audit.record(AuditAction.PICKUP_POINT_EDITED, "PICKUP_POINT", point.getId(), point.getName(),
                staff.getEmail() + " edited " + (changes.isEmpty() ? "nothing" : String.join(", ", changes)),
                request.adminNote());

        return new AdminLogisticsResponses.PickupPointSaved(point.getId(), point.getName(),
                point.getStatus(), point.isActive(),
                changes.isEmpty() ? "Nothing changed."
                        : "Updated: " + String.join(", ", changes) + ".");
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.PickupPointSaved suspendPickupPoint(
            User staff, Long id, AdminLogisticsRequests.SuspendPickupPoint request) {

        PickupPoint point = requirePoint(id);
        int onShelf = counter.recount(point);

        // Suspension blocks deposits and leaves the shelf alone. The parcels
        // already there belong to people who are coming to collect them, and a
        // suspension that stranded them would punish the recipients for a
        // decision about the operator.
        point.setStatus(PartnerStatus.SUSPENDED);
        point.setAdminNote(request.reason());
        pickupPoints.save(point);

        audit.record(AuditAction.PICKUP_POINT_SUSPENDED, "PICKUP_POINT", point.getId(),
                point.getName(),
                staff.getEmail() + " suspended the point"
                        + (onShelf == 0 ? "" : " with " + onShelf + " parcel(s) still on the shelf"),
                request.reason());

        if (point.getOperatorUser() != null) {
            notifications.send(point.getOperatorUser().getId(),
                    "Your collection point has been suspended",
                    request.reason()
                            + (onShelf == 0 ? " No parcels are affected."
                                    : " The " + onShelf + " parcel(s) you are holding must still be "
                                      + "handed to the people collecting them — no new parcels will "
                                      + "be sent to you."),
                    NotificationEvent.PICKUP_PARCEL_OVERDUE, String.valueOf(point.getId()));
        }

        return new AdminLogisticsResponses.PickupPointSaved(point.getId(), point.getName(),
                point.getStatus(), point.isActive(),
                onShelf == 0
                        ? "Suspended. No new parcels will be sent there."
                        : "Suspended for new deposits. The " + onShelf + " parcel(s) already on the "
                          + "shelf must still be collected — they are not being returned.");
    }

    private void requireShelfEmptyToClose(PickupPoint point, String what) {
        int onShelf = counter.recount(point);
        if (onShelf > 0) {
            throw new BadRequestException(
                    "There are " + onShelf + " parcel(s) on that shelf. A point cannot be " + what
                            + " while people are still coming to collect — suspend it instead, "
                            + "which stops new deposits and lets the shelf drain.");
        }
    }

    // ── Zones ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminLogisticsResponses.ZoneRow> listZones(
            String q, String countryCode, Boolean active, Pageable pageable) {
        return PagedResponse.of(zones
                .search(blankToNull(q), blankToNull(countryCode), active, pageable)
                .map(this::toZoneRow));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminLogisticsResponses.ZoneDetail readZone(Long id) {
        DeliveryZone zone = requireZone(id);
        // The geometry comes back only here. A list of thirty zones each carrying
        // a polygon of several hundred vertices is a response nobody can read and
        // a page nobody can load.
        return new AdminLogisticsResponses.ZoneDetail(toZoneRow(zone), zone.getGeometry());
    }

    private AdminLogisticsResponses.ZoneRow toZoneRow(DeliveryZone zone) {
        return new AdminLogisticsResponses.ZoneRow(
                zone.getId(), zone.getCode(), zone.getName(), zone.getDescription(),
                zone.getCountryCode(), zone.isServiceable(), zone.getUnserviceableReason(),
                zone.getPriority(), zone.isActive(),
                zone.getMinLatitude(), zone.getMaxLatitude(),
                zone.getMinLongitude(), zone.getMaxLongitude(), zone.getVertexCount(),
                rateCards.countByZoneId(zone.getId()), drivers.countCovering(zone.getId()),
                zone.getUpdatedAt(), zone.getLastEditedByUserId());
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.ZoneSaved createZone(
            User staff, AdminLogisticsRequests.CreateZone request) {

        if (zones.existsByCodeIgnoreCase(request.code())) {
            throw new BadRequestException(
                    "A zone with the code " + request.code() + " already exists.");
        }

        DeliveryZone zone = DeliveryZone.builder()
                .code(request.code().toUpperCase())
                .name(request.name().trim())
                .description(request.description())
                .countryCode(request.countryCode().toUpperCase())
                .serviceable(request.serviceable() == null || request.serviceable())
                .unserviceableReason(request.unserviceableReason())
                .priority(request.priority() == null ? 0 : request.priority())
                .active(true)
                .lastEditedByUserId(staff.getId())
                .geometry(request.geometry())
                .build();

        GeoJsonPolygon shape = zoneRegistry.applyGeometry(zone, request.geometry());
        requireBoxInCountry(zone, shape);
        zones.save(zone);

        long version = zoneRegistry.invalidate();

        audit.record(AuditAction.ZONE_CREATED, "ZONE", zone.getId(), zone.getCode(),
                staff.getEmail() + " drew the zone in " + zone.getCountryCode() + " with "
                        + shape.vertexCount() + " vertices",
                request.description());

        return saved(zone, shape, version,
                "Zone created and live. "
                        + (zone.isServiceable() ? "Deliveries here are priced from the cards that "
                                                + "apply to it."
                                               : "Marked unserviceable — home delivery and "
                                                + "collection points here will be refused."));
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.ZoneSaved patchZone(
            User staff, Long id, AdminLogisticsRequests.PatchZone request) {

        DeliveryZone zone = requireZone(id);
        List<String> changes = new ArrayList<>();

        if (request.name() != null) { zone.setName(request.name().trim()); changes.add("name"); }
        if (request.description() != null) zone.setDescription(request.description());
        if (request.priority() != null) { zone.setPriority(request.priority()); changes.add("priority"); }
        if (request.serviceable() != null && request.serviceable() != zone.isServiceable()) {
            zone.setServiceable(request.serviceable());
            changes.add(request.serviceable() ? "now serviceable" : "no longer serviceable");
        }
        if (request.unserviceableReason() != null) {
            zone.setUnserviceableReason(request.unserviceableReason());
        }
        if (Boolean.FALSE.equals(request.serviceable())
                && (zone.getUnserviceableReason() == null
                    || zone.getUnserviceableReason().isBlank())) {
            throw new BadRequestException(
                    "Say why the platform is not delivering there. Shoppers are shown this, and "
                            + "\"unavailable\" is not something anybody can act on.");
        }
        if (request.active() != null) {
            zone.setActive(request.active());
            changes.add(request.active() ? "reactivated" : "deactivated");
        }

        GeoJsonPolygon shape;
        if (request.geometry() != null && !request.geometry().isBlank()) {
            shape = zoneRegistry.applyGeometry(zone, request.geometry());
            requireBoxInCountry(zone, shape);
            changes.add("shape (" + shape.vertexCount() + " vertices)");
        } else {
            shape = zoneRegistry.parse(zone.getGeometry());
        }

        zone.setLastEditedByUserId(staff.getId());
        zones.save(zone);

        // Always, even when only the name moved. The cache holds the name, and a
        // stale entry is a stale entry.
        long version = zoneRegistry.invalidate();

        audit.record(AuditAction.ZONE_EDITED, "ZONE", zone.getId(), zone.getCode(),
                staff.getEmail() + " edited "
                        + (changes.isEmpty() ? "nothing" : String.join(", ", changes)),
                request.description());

        return saved(zone, shape, version,
                (changes.isEmpty() ? "Nothing changed, but the" : "Updated: "
                        + String.join(", ", changes) + ". The")
                        + " serviceability cache was reloaded, so this is live now rather than at "
                        + "the next restart.");
    }

    private AdminLogisticsResponses.ZoneSaved saved(DeliveryZone zone, GeoJsonPolygon shape,
                                                    long version, String message) {
        return new AdminLogisticsResponses.ZoneSaved(zone.getId(), zone.getCode(),
                shape.vertexCount(), shape.polygonCount(),
                zone.getMinLatitude(), zone.getMaxLatitude(),
                zone.getMinLongitude(), zone.getMaxLongitude(),
                version, zoneRegistry.size(), message);
    }

    /**
     * A cheap sanity check on a shape that claims to be somewhere.
     *
     * <p>Two questions, and both exist because of the same silent failure: a
     * polygon uploaded with its coordinates the wrong way round parses, stores,
     * and then contains nothing. {@link GeoJsonPolygon} catches the swap when it
     * produces a latitude beyond ±90, which is most of the world — but The
     * Gambia sits at 13°N 16°W, and swapped that is 16°N 13°E, a legal position
     * in the middle of Niger that nobody would notice for a month.
     *
     * <p>So: is it a plausible size, and is it roughly where it says it is. A
     * country this platform has no box for is not checked, because adding a
     * country should not wait on a coordinate table.
     */
    private static void requireBoxInCountry(DeliveryZone zone, GeoJsonPolygon shape) {
        double latSpan = shape.maxLatitude() - shape.minLatitude();
        double lngSpan = shape.maxLongitude() - shape.minLongitude();
        if (latSpan > 10 || lngSpan > 10) {
            throw new BadRequestException(String.format(
                    "That shape spans %.1f° of latitude and %.1f° of longitude — roughly %.0fkm "
                            + "across. That is bigger than any delivery zone should be. Check the "
                            + "file is in WGS 84 degrees and that its positions are "
                            + "[longitude, latitude] rather than the other way round.",
                    latSpan, lngSpan, Math.max(latSpan, lngSpan) * 111));
        }

        CountryBounds.of(zone.getCountryCode()).ifPresent(box -> {
            if (!box.overlaps(shape.minLatitude(), shape.maxLatitude(),
                    shape.minLongitude(), shape.maxLongitude())) {
                throw new BadRequestException(String.format(
                        "That shape sits around %.2f, %.2f, which is nowhere near %s. The usual "
                                + "cause is a file whose positions are [latitude, longitude] — "
                                + "GeoJSON wants [longitude, latitude], and swapped round a zone "
                                + "over Serrekunda ends up somewhere it will never contain an "
                                + "address.",
                        (shape.minLatitude() + shape.maxLatitude()) / 2,
                        (shape.minLongitude() + shape.maxLongitude()) / 2,
                        zone.getCountryCode()));
            }
        });
    }

    // ── Rate cards ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminLogisticsResponses.RateCardRow> listRateCards(
            Long zoneId, String countryCode, DeliveryMode mode, boolean activeOnly,
            Pageable pageable) {
        LocalDate today = LocalDate.now();
        return PagedResponse.of(rateCards
                .search(zoneId, blankToNull(countryCode), mode, activeOnly, today, pageable)
                .map(card -> toCardRow(card, today)));
    }

    private AdminLogisticsResponses.RateCardRow toCardRow(DeliveryRateCard card, LocalDate today) {
        return new AdminLogisticsResponses.RateCardRow(
                card.getId(), card.getName(),
                card.getZone() == null ? null : card.getZone().getId(),
                card.getZone() == null ? null : card.getZone().getCode(),
                card.getCountryCode(), card.getMode(), card.getCurrency(),
                card.getBaseFee(), card.getIncludedKm(), card.getPerKm(),
                card.getIncludedKg(), card.getPerKg(), card.getMinFee(), card.getMaxFee(),
                card.getFreeAbove(),
                card.getEffectiveFrom(), card.getEffectiveUntil(), card.isActive(),
                card.appliesOn(today), card.getNote(), card.getCreatedByUserId(),
                card.getCreatedAt());
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.RateCardSaved createRateCard(
            User staff, AdminLogisticsRequests.CreateRateCard request) {

        LocalDate today = LocalDate.now();
        LocalDate from = request.effectiveFrom() == null ? today : request.effectiveFrom();

        // The whole reason cards are dated. An order priced on the fifteenth was
        // priced under the card in force on the fifteenth, and a card backdated
        // to the first would make that total something nobody can reproduce.
        if (from.isBefore(today)) {
            throw new BadRequestException(
                    "A rate card cannot start in the past. Orders placed before today were priced "
                            + "under the card that was in force then, and backdating this one would "
                            + "make those totals impossible to explain. The earliest start is "
                            + today + ".");
        }

        DeliveryZone zone = null;
        if (request.zoneId() != null) {
            zone = requireZone(request.zoneId());
        }
        String country = request.countryCode() == null ? null : request.countryCode().toUpperCase();
        if (zone != null && country != null && !zone.getCountryCode().equalsIgnoreCase(country)) {
            throw new BadRequestException(
                    "Zone " + zone.getCode() + " is in " + zone.getCountryCode()
                            + ", not " + country + ".");
        }
        if (request.minFee() != null && request.maxFee() != null
                && request.minFee().compareTo(request.maxFee()) > 0) {
            throw new BadRequestException(
                    "The floor (" + request.minFee() + ") is above the ceiling ("
                            + request.maxFee() + "). Every leg would be priced at the ceiling.");
        }

        // Close whatever this supersedes, rather than letting two cards claim the
        // same day. A tie broken at read time is a price nobody can predict.
        DeliveryRateCard superseded = null;
        LocalDate endsOn = null;
        for (DeliveryRateCard existing : rateCards.findOverlapping(
                zone == null ? null : zone.getId(), country, request.mode(), from)) {
            if (existing.getEffectiveFrom().isAfter(from)
                    || existing.getEffectiveFrom().isEqual(from)) {
                throw new BadRequestException(
                        "A card for exactly this scope already starts on "
                                + existing.getEffectiveFrom() + ". Close or deactivate that one "
                                + "before writing another from the same day.");
            }
            existing.setEffectiveUntil(from.minusDays(1));
            rateCards.save(existing);
            superseded = existing;
            endsOn = existing.getEffectiveUntil();
        }

        DeliveryRateCard card = DeliveryRateCard.builder()
                .name(request.name().trim())
                .zone(zone)
                .countryCode(country)
                .mode(request.mode())
                .currency(request.currency().toUpperCase())
                .baseFee(request.baseFee())
                .includedKm(orZero(request.includedKm()))
                .perKm(orZero(request.perKm()))
                .includedKg(orZero(request.includedKg()))
                .perKg(orZero(request.perKg()))
                .minFee(request.minFee())
                .maxFee(request.maxFee())
                .freeAbove(request.freeAbove())
                .effectiveFrom(from)
                .note(request.note())
                .active(true)
                .createdByUserId(staff.getId())
                .build();
        rateCards.save(card);

        audit.record(AuditAction.RATE_CARD_CHANGED, "RATE_CARD", card.getId(), card.getName(),
                staff.getEmail() + " wrote a card for "
                        + (zone != null ? "zone " + zone.getCode()
                                        : country != null ? country : "everywhere")
                        + (request.mode() == null ? "" : " / " + request.mode())
                        + " from " + from
                        + (superseded == null ? "" : ", superseding card " + superseded.getId()),
                request.note());

        return new AdminLogisticsResponses.RateCardSaved(toCardRow(card, today),
                superseded == null ? null : superseded.getId(), endsOn,
                (from.isEqual(today) ? "Live from today. " : "Live from " + from + ". ")
                        + (superseded == null
                                ? "Nothing was priced this way before."
                                : "Card " + superseded.getId() + " now ends on " + endsOn
                                  + " — legs before that are still priced under it."));
    }

    @Override
    @Transactional
    public AdminLogisticsResponses.RateCardSaved patchRateCard(
            User staff, Long id, AdminLogisticsRequests.PatchRateCard request) {

        DeliveryRateCard card = rateCards.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("No such rate card."));
        LocalDate today = LocalDate.now();
        List<String> changes = new ArrayList<>();

        if (request.name() != null) { card.setName(request.name().trim()); changes.add("name"); }
        if (request.note() != null) { card.setNote(request.note()); changes.add("note"); }

        if (request.effectiveUntil() != null) {
            if (request.effectiveUntil().isBefore(today)) {
                throw new BadRequestException(
                        "A card cannot be closed in the past — legs priced under it between then "
                                + "and now would become legs no card explains. The earliest "
                                + "closing day is " + today + ".");
            }
            if (request.effectiveUntil().isBefore(card.getEffectiveFrom())) {
                throw new BadRequestException(
                        "That card starts on " + card.getEffectiveFrom()
                                + " and you are ending it before it begins.");
            }
            card.setEffectiveUntil(request.effectiveUntil());
            changes.add("ends " + request.effectiveUntil());
        }

        if (request.active() != null && request.active() != card.isActive()) {
            card.setActive(request.active());
            changes.add(request.active() ? "reactivated" : "deactivated");
        }

        rateCards.save(card);

        audit.record(AuditAction.RATE_CARD_CHANGED, "RATE_CARD", card.getId(), card.getName(),
                staff.getEmail() + " changed "
                        + (changes.isEmpty() ? "nothing" : String.join(", ", changes)),
                request.note());

        return new AdminLogisticsResponses.RateCardSaved(toCardRow(card, today), null, null,
                changes.isEmpty() ? "Nothing changed."
                        : "Updated: " + String.join(", ", changes) + ". The card's figures are "
                          + "unchanged — write a new card from a new date to change a price.");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminLogisticsResponses.RateCardPreview> previewRates(Long zoneId,
                                                                     String countryCode) {
        LocalDate today = LocalDate.now();
        List<AdminLogisticsResponses.RateCardPreview> previews = new ArrayList<>();

        for (DeliveryMode mode : DeliveryMode.values()) {
            RateCardRegistry.Resolved resolved =
                    rateCardRegistry.resolve(zoneId, blankToNull(countryCode), mode, today);
            LegRate rate = resolved.rate();
            previews.add(new AdminLogisticsResponses.RateCardPreview(
                    resolved.cardId(), resolved.cardName(), resolved.source(), rate.currency(),
                    rate.priceLeg(PREVIEW_LEGS[0][0], PREVIEW_LEGS[0][1], DeliveryScope.REGIIONAL, mode),
                    rate.priceLeg(PREVIEW_LEGS[1][0], PREVIEW_LEGS[1][1], DeliveryScope.REGIIONAL, mode),
                    rate.priceLeg(PREVIEW_LEGS[2][0], PREVIEW_LEGS[2][1], DeliveryScope.NATIONAL, mode)));
        }
        return previews;
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private Driver requireDriver(Long id) {
        return drivers.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("No such driver."));
    }

    private PickupPoint requirePoint(Long id) {
        return pickupPoints.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("No such collection point."));
    }

    private DeliveryZone requireZone(Long id) {
        return zones.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("No such zone."));
    }

    /** Resolves codes to zones, naming every one that does not exist rather than the first. */
    private Set<DeliveryZone> resolveZones(List<String> codes) {
        Set<DeliveryZone> resolved = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        if (codes == null) return resolved;
        for (String code : codes) {
            if (code == null || code.isBlank()) continue;
            zones.findByCodeIgnoreCase(code.trim())
                    .ifPresentOrElse(resolved::add, () -> missing.add(code.trim()));
        }
        if (!missing.isEmpty()) {
            // All of them, so somebody fixing a list of six typos does it once
            // rather than six times.
            throw new BadRequestException("No zone with the code: " + String.join(", ", missing));
        }
        return resolved;
    }

    private static List<AdminLogisticsResponses.ZoneBadge> badges(Set<DeliveryZone> coverage) {
        List<AdminLogisticsResponses.ZoneBadge> badges = new ArrayList<>();
        for (DeliveryZone zone : coverage) {
            badges.add(new AdminLogisticsResponses.ZoneBadge(zone.getId(), zone.getCode(),
                    zone.getName(), zone.isServiceable()));
        }
        return badges;
    }

    private static String zoneNames(Set<DeliveryZone> coverage) {
        return coverage.stream().map(DeliveryZone::getName).reduce((a, b) -> a + ", " + b)
                .orElse("nowhere");
    }

    private void notifyDriver(Driver driver, String title, String message) {
        if (driver.getUser() == null) return;
        notifications.send(driver.getUser().getId(), title, message,
                NotificationEvent.DELIVERY_OFFERED, String.valueOf(driver.getId()));
    }

    private static String nameOf(User user) {
        if (user == null) return "unknown";
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getEmail() : full;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
