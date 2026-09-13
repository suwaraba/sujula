package com.sujula.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sujula.dto.response.pickup.PickupResponses;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.service.pickup.PickupPointService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which half of this surface is open, and which is not.
 *
 * <p>Finding a counter is public on purpose: a shopper picks where to collect
 * before signing in, and requiring an account would hide the option from exactly
 * the people most likely to want it. Running one is not, because those rows lead
 * to recipients' names and phone numbers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PickupRoutingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PickupPointService pickup;

    // ── Open ─────────────────────────────────────────────────────────────────

    @Test
    void findingACounterNeedsNoAccount() throws Exception {
        when(pickup.search(any())).thenReturn(
                new PickupResponses.PublicPoints(List.of(), 13.44, -16.67, 5.0, null));
        when(pickup.publicPoint(1L)).thenReturn(publicPoint());

        mvc.perform(get("/pickup-points").param("lat", "13.44").param("lng", "-16.67"))
                .andExpect(status().isOk());
        mvc.perform(get("/pickup-points/1")).andExpect(status().isOk());
    }

    @Test
    void thePublicSearchBindsItsParameters() throws Exception {
        when(pickup.search(any())).thenReturn(
                new PickupResponses.PublicPoints(List.of(), null, null, null, null));

        mvc.perform(get("/pickup-points")
                        .param("lat", "13.4429").param("lng", "-16.6776").param("radius", "8"))
                .andExpect(status().isOk());

        verify(pickup).search(new com.sujula.dto.request.pickup.PickupRequests.NearbySearch(
                13.4429, -16.6776, 8.0, null));
    }

    @Test
    void aPublicCounterMayBeCachedBecauseItCarriesNothingPrivate() throws Exception {
        when(pickup.publicPoint(1L)).thenReturn(publicPoint());

        mvc.perform(get("/pickup-points/1"))
                .andExpect(status().isOk())
                // An address and opening hours change rarely, and the capacity
                // is a band rather than a live count for exactly this reason.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("public")));
    }

    // ── Closed ───────────────────────────────────────────────────────────────

    @Test
    void everyOperatorRouteRefusesAnAnonymousCaller() throws Exception {
        mvc.perform(post("/pickup/applications").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/pickup/points")).andExpect(status().isForbidden());
        mvc.perform(patch("/pickup/points/1").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/pickup/points/1/parcels")).andExpect(status().isForbidden());
        mvc.perform(post("/pickup/points/1/parcels/2/accept")
                        .contentType("application/json").content("{\"code\":\"123456\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/pickup/points/1/parcels/2/reject")
                        .contentType("application/json").content("{\"reason\":\"DAMAGED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/pickup/points/1/parcels/2/release")
                        .contentType("application/json")
                        .content("{\"code\":\"123456\",\"collectedByName\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/pickup/points/1/parcels/2/return")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/pickup/points/1/parcels/2/resend-code"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/pickup/points/1/earnings")).andExpect(status().isForbidden());
    }

    @Test
    void andTheServiceIsNeverReached() throws Exception {
        mvc.perform(get("/pickup/points/1/parcels"));
        mvc.perform(post("/pickup/points/1/parcels/2/resend-code"));

        // Refused by the chain rather than by a check inside the service. A
        // recipient's name must not depend on a service remembering to look at
        // who is asking.
        verify(pickup, never()).parcels(any(), any());
        verify(pickup, never()).resendCode(any(), any(), any());
    }

    // ── The right handler, with the right arguments ──────────────────────────

    @Test
    void releaseCarriesBothPathIdsAndTheCallersOwnId() throws Exception {
        when(pickup.release(eq(950L), eq(1L), eq(2L), any())).thenReturn(
                new PickupResponses.ParcelReleased(2L, "SHP-1", ShipmentStatus.DELIVERED,
                        LocalDateTime.now(), "Isatou Ceesay", true, 0, false, "ok"));

        mvc.perform(post("/pickup/points/1/parcels/2/release")
                        .with(authentication(operatorAuth())).with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"123456\",\"collectedByName\":\"Isatou Ceesay\"}"))
                .andExpect(status().isOk());

        // 950 comes from the principal; there is no operator id in the path.
        verify(pickup).release(eq(950L), eq(1L), eq(2L), any());
    }

    @Test
    void releaseWithoutANameNeverReachesTheService() throws Exception {
        mvc.perform(post("/pickup/points/1/parcels/2/release")
                        .with(authentication(operatorAuth())).with(csrf())
                        .contentType("application/json").content("{\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest());

        // A code alone would let anybody who overheard it collect.
        verify(pickup, never()).release(any(), any(), any(), any());
    }

    @Test
    void anUnknownRejectionReasonIsRefusedBeforeTheServiceSeesIt() throws Exception {
        mvc.perform(post("/pickup/points/1/parcels/2/reject")
                        .with(authentication(operatorAuth())).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"BORED\"}"))
                .andExpect(status().isBadRequest());

        verify(pickup, never()).reject(any(), any(), any(), any());
    }

    @Test
    void returningWorksWithNoBodyAtAll() throws Exception {
        when(pickup.returnToVendor(eq(950L), eq(1L), eq(2L), any())).thenReturn(
                new PickupResponses.ParcelReturning(2L, "SHP-1", ShipmentStatus.RETURNED,
                        LocalDateTime.now(), 3, false, "ok"));

        // There is nothing an operator has to say: the deadline has passed or it
        // has not.
        mvc.perform(post("/pickup/points/1/parcels/2/return")
                        .with(authentication(operatorAuth())).with(csrf()))
                .andExpect(status().isOk());

        verify(pickup).returnToVendor(eq(950L), eq(1L), eq(2L), any());
    }

    @Test
    void theApplicationsRouteIsNotReadAsAPointId() throws Exception {
        // /pickup/applications sits beside /pickup/points/{id}; a variable
        // pattern winning here would 400 an application as "point
        // 'applications' not found".
        when(pickup.apply(eq(950L), any())).thenReturn(
                new PickupResponses.ApplicationSubmitted(1L, "Kiosk", PartnerStatus.PENDING,
                        LocalDateTime.now(), "ok"));

        mvc.perform(post("/pickup/applications")
                        .with(authentication(operatorAuth())).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"name":"Westfield Kiosk","addressStreet":"Westfield Junction",
                                 "city":"Serekunda","lat":13.4429,"lng":-16.6776,
                                 "contactPhone":"+2203100008","managerName":"Isatou",
                                 "openingHours":"Mon-Sat 08:00-20:00","licenseNumber":"x",
                                 "capacity":40}"""))
                .andExpect(status().isOk());

        verify(pickup).apply(eq(950L), any());
        verify(pickup, never()).updatePoint(any(), any(), any());
    }

    @Test
    void anApplicationWithNoPositionIsRefused() throws Exception {
        // Most addresses here do not resolve to a point, so the position is the
        // field that actually navigates a driver to the counter.
        mvc.perform(post("/pickup/applications")
                        .with(authentication(operatorAuth())).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"name":"Kiosk","addressStreet":"Somewhere","city":"Serekunda",
                                 "contactPhone":"+220","managerName":"X",
                                 "openingHours":"daily"}"""))
                .andExpect(status().isBadRequest());

        verify(pickup, never()).apply(any(), any());
    }

    // ── What the operator's views say about themselves ───────────────────────

    @Test
    void theOperatorsOwnViewsAreNeverCached() throws Exception {
        when(pickup.parcels(950L, 1L)).thenReturn(new PickupResponses.Parcels(
                List.of(), List.of(), List.of(), 0, 10,
                PickupResponses.Capacity.AVAILABLE, null));

        mvc.perform(get("/pickup/points/1/parcels").with(authentication(operatorAuth())))
                .andExpect(status().isOk())
                // A counter's tablet sits on a shop counter all day and is
                // frequently shared. This carries recipients' names.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("private")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static PickupResponses.PublicPoint publicPoint() {
        return new PickupResponses.PublicPoint(1L, "Westfield Junction Kiosk",
                "Westfield Junction", "Serekunda", "GM", 13.4429, -16.6776, 0.4,
                "Mon-Sat 08:00-20:00", PickupResponses.Capacity.AVAILABLE,
                true, null, null, "+2203100008", null);
    }

    private static org.springframework.security.core.Authentication operatorAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                operator(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_PICKUP_OPERATOR")));
    }

    private static com.sujula.model.user.User operator() {
        com.sujula.model.user.User user = new com.sujula.model.user.User();
        user.setId(950L);
        user.setEmail("isatou.pickup@sujula.gm");
        user.setRole(com.sujula.model.constant.UserRole.PICKUP_OPERATOR);
        return user;
    }
}
