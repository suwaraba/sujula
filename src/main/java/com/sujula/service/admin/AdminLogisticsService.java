package com.sujula.service.admin;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.admin.AdminLogisticsRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminLogisticsResponses;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.user.User;

/**
 * The shape of the delivery network: who carries, from where, to where, at what
 * price.
 *
 * <p>All four of these are delivery-side. Nothing here takes a payer, a display
 * currency, or a buyer's country — a driver is approved for the zones a parcel
 * travels to, a counter exists at a place, and a rate card prices a journey. The
 * payment surface is {@code AdminMoneyService}, and the two are kept apart on
 * purpose.
 */
public interface AdminLogisticsService {

    // ── Drivers ──────────────────────────────────────────────────────────────

    PagedResponse<AdminLogisticsResponses.DriverRow> listDrivers(
            String q, DriverStatus status, String countryCode, String zoneCode,
            Boolean availableOnly, Pageable pageable);

    AdminLogisticsResponses.DriverDecision approveDriver(
            User staff, Long driverId, AdminLogisticsRequests.ApproveDriver request);

    AdminLogisticsResponses.DriverDecision suspendDriver(
            User staff, Long driverId, AdminLogisticsRequests.SuspendDriver request);

    AdminLogisticsResponses.DriverDecision setDriverZones(
            User staff, Long driverId, AdminLogisticsRequests.SetDriverZones request);

    // ── Pickup points ────────────────────────────────────────────────────────

    PagedResponse<AdminLogisticsResponses.PickupPointRow> listPickupPoints(
            String q, PartnerStatus status, String countryCode, Boolean overdueOnly,
            Boolean fullOnly, Pageable pageable);

    AdminLogisticsResponses.PickupPointSaved createPickupPoint(
            User staff, AdminLogisticsRequests.CreatePickupPoint request);

    AdminLogisticsResponses.PickupPointSaved patchPickupPoint(
            User staff, Long id, AdminLogisticsRequests.PatchPickupPoint request);

    AdminLogisticsResponses.PickupPointSaved suspendPickupPoint(
            User staff, Long id, AdminLogisticsRequests.SuspendPickupPoint request);

    // ── Zones ────────────────────────────────────────────────────────────────

    PagedResponse<AdminLogisticsResponses.ZoneRow> listZones(
            String q, String countryCode, Boolean active, Pageable pageable);

    AdminLogisticsResponses.ZoneDetail readZone(Long id);

    AdminLogisticsResponses.ZoneSaved createZone(
            User staff, AdminLogisticsRequests.CreateZone request);

    AdminLogisticsResponses.ZoneSaved patchZone(
            User staff, Long id, AdminLogisticsRequests.PatchZone request);

    // ── Rate cards ───────────────────────────────────────────────────────────

    PagedResponse<AdminLogisticsResponses.RateCardRow> listRateCards(
            Long zoneId, String countryCode, DeliveryMode mode, boolean activeOnly,
            Pageable pageable);

    AdminLogisticsResponses.RateCardSaved createRateCard(
            User staff, AdminLogisticsRequests.CreateRateCard request);

    AdminLogisticsResponses.RateCardSaved patchRateCard(
            User staff, Long id, AdminLogisticsRequests.PatchRateCard request);

    /** What the live cards would charge for three representative legs. */
    List<AdminLogisticsResponses.RateCardPreview> previewRates(Long zoneId, String countryCode);
}
