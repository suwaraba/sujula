package com.sujula.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.admin.AdminLogisticsRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminLogisticsResponses;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.service.admin.AdminLogisticsService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The shape of the delivery network.
 *
 * <p>Every write here is an administrator's — support can read a driver's
 * record while helping somebody on the telephone, but who may carry, where a
 * counter is, and what carriage costs are decisions rather than lookups.
 *
 * <p>Nothing on this controller takes a payer. A zone is drawn round where
 * parcels go, a driver is approved for the places they can reach, and a rate
 * card prices a journey — none of which has anything to do with where the person
 * paying happens to be sitting.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("isAuthenticated()")
@Tag(name = "admin-logistics", description = "Drivers, collection points, zones and rate cards")
public class AdminLogisticsController {

    private final AdminLogisticsService logistics;
    private final StaffCaller staff;
    private final IdempotencyService idempotency;

    public AdminLogisticsController(AdminLogisticsService logistics, StaffCaller staff,
                                    IdempotencyService idempotency) {
        this.logistics = logistics;
        this.staff = staff;
        this.idempotency = idempotency;
    }

    // ── Drivers ──────────────────────────────────────────────────────────────

    @GetMapping("/drivers")
    @Operation(summary = "Drivers, with what they are carrying and how they answer",
               description = "Acceptance score alone reads as a judgement about a person. Beside "
                       + "it are how long since their phone reported a position and how many jobs "
                       + "they are holding, because a driver at 40% with three parcels and no ping "
                       + "for four hours is a dead battery rather than an unreliable driver, and "
                       + "those want opposite responses.")
    public ResponseEntity<PagedResponse<AdminLogisticsResponses.DriverRow>> drivers(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DriverStatus status,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) String zoneCode,
            @RequestParam(required = false) Boolean availableOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(logistics.listDrivers(q, status, countryCode, zoneCode, availableOnly,
                paged(page, size)));
    }

    @PostMapping("/drivers/{driverId}/approve")
    @Operation(summary = "Let a driver start carrying",
               description = "Refused without an identity document, and refused on an expired "
                       + "licence. A driver holds goods worth more than they earn in a month and "
                       + "meets families at their homes; approving one who submitted nothing is "
                       + "approving a name.")
    public ResponseEntity<AdminLogisticsResponses.DriverDecision> approveDriver(
            Authentication authentication, @PathVariable Long driverId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminLogisticsRequests.ApproveDriver request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.drivers.approve:" + driverId),
                key, request, 200, AdminLogisticsResponses.DriverDecision.class,
                () -> logistics.approveDriver(acting, driverId, request)));
    }

    @PostMapping("/drivers/{driverId}/suspend")
    @Operation(summary = "Stop offering a driver work",
               description = "Their open jobs are counted and named back, not cancelled. A "
                       + "suspension is a decision about the person; the parcels in their van "
                       + "belong to other people and have to be moved one at a time by somebody "
                       + "looking at each, or the custody chain ends up with a hole in it.")
    public ResponseEntity<AdminLogisticsResponses.DriverDecision> suspendDriver(
            Authentication authentication, @PathVariable Long driverId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminLogisticsRequests.SuspendDriver request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.drivers.suspend:" + driverId),
                key, request, 200, AdminLogisticsResponses.DriverDecision.class,
                () -> logistics.suspendDriver(acting, driverId, request)));
    }

    @PatchMapping("/drivers/{driverId}/zones")
    @Operation(summary = "Set where a driver may be offered work",
               description = "Replaces the whole list rather than adding to it, so what is sent "
                       + "is what the driver covers. A zone in a country the driver does not work "
                       + "in is refused: the free-text area on their application is what they "
                       + "typed, and this is what the platform decided.")
    public ResponseEntity<AdminLogisticsResponses.DriverDecision> driverZones(
            Authentication authentication, @PathVariable Long driverId,
            @Valid @RequestBody AdminLogisticsRequests.SetDriverZones request) {
        return ResponseEntity.ok(
                logistics.setDriverZones(staff.decider(authentication), driverId, request));
    }

    // ── Pickup points ────────────────────────────────────────────────────────

    @GetMapping("/pickup-points")
    @Operation(summary = "Collection points, with what is sitting on each shelf",
               description = "Overdue parcels and remaining space are the two numbers that "
                       + "matter: a full counter is refusing deposits right now, and a counter "
                       + "with parcels past their deadline has people who never came. Filter on "
                       + "either to find the handful that need somebody today.")
    public ResponseEntity<PagedResponse<AdminLogisticsResponses.PickupPointRow>> pickupPoints(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PartnerStatus status,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) Boolean overdueOnly,
            @RequestParam(required = false) Boolean fullOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(logistics.listPickupPoints(q, status, countryCode, overdueOnly, fullOnly,
                paged(page, size)));
    }

    @PostMapping("/pickup-points")
    @Operation(summary = "Open a counter",
               description = "The pin is required and the postal code is not, which is the right "
                       + "way round for this market: most addresses here have no postal code, and "
                       + "the pin is what a driver actually navigates to. Created approved — the "
                       + "approval step exists to review an application from outside, and there is "
                       + "no application here.")
    public ResponseEntity<AdminLogisticsResponses.PickupPointSaved> createPickupPoint(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminLogisticsRequests.CreatePickupPoint request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.pickup-points.create"),
                key, request, HttpStatus.CREATED.value(),
                AdminLogisticsResponses.PickupPointSaved.class,
                () -> logistics.createPickupPoint(acting, request)));
    }

    @PatchMapping("/pickup-points/{pointId}")
    @Operation(summary = "Edit a counter",
               description = "Lowering capacity below what is already on the shelf is refused, "
                       + "and switching a point off with parcels still stored is refused — "
                       + "suspend it instead, which stops new deposits and lets the shelf drain. "
                       + "The people coming to collect did nothing wrong.")
    public ResponseEntity<AdminLogisticsResponses.PickupPointSaved> patchPickupPoint(
            Authentication authentication, @PathVariable Long pointId,
            @Valid @RequestBody AdminLogisticsRequests.PatchPickupPoint request) {
        return ResponseEntity.ok(
                logistics.patchPickupPoint(staff.decider(authentication), pointId, request));
    }

    @PostMapping("/pickup-points/{pointId}/suspend")
    @Operation(summary = "Stop new parcels going to a counter",
               description = "Blocks deposits and leaves the shelf alone. The parcels already "
                       + "there belong to people who are coming for them, and a suspension that "
                       + "stranded those would punish recipients for a decision about the "
                       + "operator.")
    public ResponseEntity<AdminLogisticsResponses.PickupPointSaved> suspendPickupPoint(
            Authentication authentication, @PathVariable Long pointId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminLogisticsRequests.SuspendPickupPoint request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(),
                        "admin.pickup-points.suspend:" + pointId),
                key, request, 200, AdminLogisticsResponses.PickupPointSaved.class,
                () -> logistics.suspendPickupPoint(acting, pointId, request)));
    }

    // ── Zones ────────────────────────────────────────────────────────────────

    @GetMapping("/zones")
    @Operation(summary = "Zones, without their polygons",
               description = "The bounding box and the vertex count, not the shape itself — "
                       + "thirty zones each carrying several hundred coordinates is a response "
                       + "nobody can read. Fetch one zone to get its geometry back.")
    public ResponseEntity<PagedResponse<AdminLogisticsResponses.ZoneRow>> zones(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(logistics.listZones(q, countryCode, active, paged(page, size)));
    }

    @GetMapping("/zones/{zoneId}")
    @Operation(summary = "One zone, with the GeoJSON exactly as it was uploaded",
               description = "Verbatim, not re-serialised from our own parse. It is what an "
                       + "administrator can hand to another system and what they can be shown "
                       + "back — a shape that changed on the way through is a shape nobody can "
                       + "audit.")
    public ResponseEntity<AdminLogisticsResponses.ZoneDetail> zone(
            Authentication authentication, @PathVariable Long zoneId) {
        staff.staff(authentication);
        return uncached(logistics.readZone(zoneId));
    }

    @PostMapping("/zones")
    @Operation(summary = "Draw a zone",
               description = "Takes a GeoJSON Polygon, MultiPolygon, Feature or "
                       + "FeatureCollection, because those are what the drawing tools actually "
                       + "produce. A file whose positions are [latitude, longitude] rather than "
                       + "GeoJSON's [longitude, latitude] is refused by name: swapped round, a "
                       + "zone over Banjul is a zone in the Atlantic that silently contains "
                       + "nothing.")
    public ResponseEntity<AdminLogisticsResponses.ZoneSaved> createZone(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminLogisticsRequests.CreateZone request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.zones.create"),
                key, request, HttpStatus.CREATED.value(),
                AdminLogisticsResponses.ZoneSaved.class,
                () -> logistics.createZone(acting, request)));
    }

    @PatchMapping("/zones/{zoneId}")
    @Operation(summary = "Edit a zone, and reload the serviceability cache",
               description = "Always invalidates, even when only the name moved. The response "
                       + "carries the cache version afterwards so the edit is visibly live rather "
                       + "than assumed to be — the reason somebody edits a zone is usually that "
                       + "parcels are being quoted wrong right now, and 'at the next restart' is "
                       + "not an answer.")
    public ResponseEntity<AdminLogisticsResponses.ZoneSaved> patchZone(
            Authentication authentication, @PathVariable Long zoneId,
            @Valid @RequestBody AdminLogisticsRequests.PatchZone request) {
        return ResponseEntity.ok(logistics.patchZone(staff.decider(authentication), zoneId, request));
    }

    // ── Rate cards ───────────────────────────────────────────────────────────

    @GetMapping("/rate-cards")
    @Operation(summary = "What carriage costs, and from when",
               description = "Every card ever written, newest start first, each saying whether it "
                       + "is the one pricing legs today. The history is the point: a figure on an "
                       + "order from March is only explicable against the card that was in force "
                       + "in March.")
    public ResponseEntity<PagedResponse<AdminLogisticsResponses.RateCardRow>> rateCards(
            Authentication authentication,
            @RequestParam(required = false) Long zoneId,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) DeliveryMode mode,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(logistics.listRateCards(zoneId, countryCode, mode, activeOnly,
                paged(page, size)));
    }

    @GetMapping("/rate-cards/preview")
    @Operation(summary = "What the live cards would charge for three sample legs",
               description = "An administrator's own check that what they wrote prices what they "
                       + "meant. Five kilometres with one kilo, twenty with three, a hundred with "
                       + "ten — enough to see a decimal point in the wrong place before a "
                       + "shopper does.")
    public ResponseEntity<List<AdminLogisticsResponses.RateCardPreview>> previewRates(
            Authentication authentication,
            @RequestParam(required = false) Long zoneId,
            @RequestParam(required = false) String countryCode) {
        staff.staff(authentication);
        return uncached(logistics.previewRates(zoneId, countryCode));
    }

    @PostMapping("/rate-cards")
    @Operation(summary = "Write a card, effective from a date",
               description = "Never in the past. Orders placed before today were priced under "
                       + "whatever was in force then, and backdating a card would make those "
                       + "totals impossible to explain. A card that overlaps an existing one for "
                       + "the same scope closes it the day before rather than competing with it — "
                       + "the response names which card was superseded, because superseding is how "
                       + "a price changes and doing it by accident changes a price nobody meant "
                       + "to.")
    public ResponseEntity<AdminLogisticsResponses.RateCardSaved> createRateCard(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminLogisticsRequests.CreateRateCard request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.rate-cards.create"),
                key, request, HttpStatus.CREATED.value(),
                AdminLogisticsResponses.RateCardSaved.class,
                () -> logistics.createRateCard(acting, request)));
    }

    @PatchMapping("/rate-cards/{cardId}")
    @Operation(summary = "Rename a card, note it, or close it from a date",
               description = "Deliberately not its numbers. A card's figures are what orders were "
                       + "priced under; editing them rewrites history. To change a price, write a "
                       + "new card from a new date — which is what the POST does.")
    public ResponseEntity<AdminLogisticsResponses.RateCardSaved> patchRateCard(
            Authentication authentication, @PathVariable Long cardId,
            @Valid @RequestBody AdminLogisticsRequests.PatchRateCard request) {
        return ResponseEntity.ok(
                logistics.patchRateCard(staff.decider(authentication), cardId, request));
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private static <T> ResponseEntity<T> uncached(T body) {
        // Drivers' positions, operators' names, counters' addresses. A shared
        // cache holding any of it would serve one agent's screen to the next
        // person through the proxy.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
    }
}
