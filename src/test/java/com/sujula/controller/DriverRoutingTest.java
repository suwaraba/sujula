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

import com.sujula.dto.response.driver.DriverResponses;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.service.StorageService;
import com.sujula.service.driver.DriverCustodyService;
import com.sujula.service.driver.DriverProfileService;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who reaches the driver's app, and what the responses say about themselves.
 *
 * <p>What sits behind these routes is the sharpest data on the platform:
 * recipients' home addresses and phone numbers, and where a driver has been all
 * day. Both belong to people who never agreed to be visible to anybody but the
 * person bringing a parcel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DriverRoutingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DriverProfileService profiles;

    @MockitoBean
    private DriverCustodyService custody;

    @MockitoBean
    private StorageService storage;

    private static final String HANDOVER =
            "{\"code\":\"123456\",\"lat\":13.4384,\"lng\":-16.6781,\"photoUrl\":\"https://m.invalid/p.jpg\"}";

    // ── Nothing here is open ─────────────────────────────────────────────────

    @Test
    void everyDriverRouteRefusesAnAnonymousCaller() throws Exception {
        mvc.perform(post("/driver/profile").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/driver/profile")).andExpect(status().isForbidden());
        mvc.perform(patch("/driver/profile").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/driver/availability").contentType("application/json")
                        .content("{\"availability\":\"ONLINE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/location").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/driver/assignments")).andExpect(status().isForbidden());
        mvc.perform(post("/driver/assignments/1/accept")).andExpect(status().isForbidden());
        mvc.perform(post("/driver/assignments/1/decline").contentType("application/json")
                        .content("{\"reason\":\"too far\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/driver/shipments/1")).andExpect(status().isForbidden());
        mvc.perform(post("/driver/shipments/1/arrived-at-origin")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/shipments/1/collect")
                        .contentType("application/json").content(HANDOVER))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/shipments/1/deposit-at-pickup")
                        .contentType("application/json").content(HANDOVER))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/shipments/1/deliver")
                        .contentType("application/json").content(HANDOVER))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/shipments/1/delivery-failed")
                        .contentType("application/json").content("{\"reason\":\"NOBODY_HOME\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/shipments/1/request-recipient-code"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/shipments/1/transfer")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/custody-events/sync")
                        .contentType("application/json").content("{\"events\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/driver/earnings")).andExpect(status().isForbidden());
        mvc.perform(get("/driver/history")).andExpect(status().isForbidden());
        mvc.perform(post("/driver/evidence/presign").param("contentType", "image/jpeg"))
                .andExpect(status().isForbidden());
    }

    @Test
    void andNeitherServiceIsEverReached() throws Exception {
        mvc.perform(get("/driver/shipments/1"));
        mvc.perform(post("/driver/shipments/1/request-recipient-code"));
        mvc.perform(get("/driver/earnings"));

        // Refused by the chain rather than by a check inside a service. A
        // recipient's address must not depend on a service remembering to look
        // at who is asking.
        verify(custody, never()).shipment(any(), any());
        verify(custody, never()).requestRecipientCode(any(), any());
        verify(profiles, never()).earnings(any(), any(), any());
    }

    // ── The right handler, with the right arguments ──────────────────────────

    @Test
    void collectReachesCollectWithTheCallersOwnId() throws Exception {
        when(custody.collect(eq(950L), eq(11L), any())).thenReturn(recorded());

        mvc.perform(post("/driver/shipments/11/collect")
                        .with(authentication(driverAuth())).with(csrf())
                        .contentType("application/json").content(HANDOVER))
                .andExpect(status().isOk());

        // 950 comes from the principal. There is no id in the URL a caller
        // could change to open somebody else's parcel.
        verify(custody).collect(eq(950L), eq(11L), any());
    }

    @Test
    void deliverAndDepositAreDistinctRoutesRatherThanOneSwallowingTheOther() throws Exception {
        when(custody.deliver(eq(950L), eq(11L), any())).thenReturn(recorded());

        mvc.perform(post("/driver/shipments/11/deliver")
                        .with(authentication(driverAuth())).with(csrf())
                        .contentType("application/json").content(HANDOVER))
                .andExpect(status().isOk());

        verify(custody).deliver(eq(950L), eq(11L), any());
        verify(custody, never()).depositAtPickup(any(), any(), any());
    }

    @Test
    void theSyncRouteIsNotReadAsAShipmentId() throws Exception {
        // /driver/custody-events/sync sits beside /driver/shipments/{id}; if the
        // variable pattern won, a day's offline work would 400 as "shipment
        // 'custody-events' not found".
        when(custody.sync(eq(950L), any())).thenReturn(
                new DriverResponses.SyncResult(0, 0, 0, 0, List.of(), "ok"));

        mvc.perform(post("/driver/custody-events/sync")
                        .with(authentication(driverAuth())).with(csrf())
                        .contentType("application/json")
                        .content("{\"events\":[{\"shipmentId\":1,\"type\":\"COLLECTED\","
                                + "\"clientEventId\":\"e1\",\"capturedAt\":\"2026-09-13T10:00:00\"}]}"))
                .andExpect(status().isOk());

        verify(custody).sync(eq(950L), any());
        verify(custody, never()).shipment(any(), any());
    }

    @Test
    void aHandoverWithNoCodeIsNowTheServicesQuestionRatherThanTheBindersOne() throws Exception {
        // The check moved rather than went away. There is exactly one handover
        // with nobody to read a code out — a delivery to a recipient who has
        // authorised a safe drop — and whether that is the case depends on the
        // parcel, which the binder cannot see. So the controller lets a codeless
        // body through and DriverCustodyService refuses it unless an
        // authorisation stands in for the code. The refusal itself is asserted
        // in DriverCustodyServiceTest, against a real shipment.
        when(custody.collect(eq(950L), eq(11L), any())).thenReturn(recorded());

        mvc.perform(post("/driver/shipments/11/collect")
                        .with(authentication(driverAuth())).with(csrf())
                        .contentType("application/json").content("{\"lat\":13.4,\"lng\":-16.6}"))
                .andExpect(status().isOk());

        verify(custody).collect(eq(950L), eq(11L), any());
    }

    @Test
    void aCodeOfTheWrongShapeIsRefusedBeforeTheServiceSeesIt() throws Exception {
        mvc.perform(post("/driver/shipments/11/deliver")
                        .with(authentication(driverAuth())).with(csrf())
                        .contentType("application/json").content("{\"code\":\"abc\"}"))
                .andExpect(status().isBadRequest());

        verify(custody, never()).deliver(any(), any(), any());
    }

    @Test
    void anEmptySyncBatchIsRefusedRatherThanProcessed() throws Exception {
        mvc.perform(post("/driver/custody-events/sync")
                        .with(authentication(driverAuth())).with(csrf())
                        .contentType("application/json").content("{\"events\":[]}"))
                .andExpect(status().isBadRequest());

        verify(custody, never()).sync(any(), any());
    }

    @Test
    void aDeclineWithNoReasonIsRefused() throws Exception {
        mvc.perform(post("/driver/assignments/1/decline")
                        .with(authentication(driverAuth())).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        // The reason is what tells dispatch whether to re-offer nearby.
        verify(profiles, never()).decline(any(), any(), any());
    }

    @Test
    void availabilityBindsTheEnumFromTheBody() throws Exception {
        when(profiles.setAvailability(eq(950L), any())).thenReturn(
                new DriverResponses.AvailabilitySet(true, LocalDateTime.now(), "ok"));

        mvc.perform(put("/driver/availability")
                        .with(authentication(driverAuth())).with(csrf())
                        .contentType("application/json").content("{\"availability\":\"ONLINE\"}"))
                .andExpect(status().isOk());

        verify(profiles).setAvailability(eq(950L), any());
    }

    // ── Somewhere to put a photograph ────────────────────────────────────────

    @Test
    void presigningEvidenceNamesTheFileAfterTheCallerAndNeverTheParcel() throws Exception {
        when(storage.presignUpload(eq("custody"), any(), eq("image/jpeg"), any()))
                .thenReturn("https://storage.invalid/put");
        when(storage.publicUrl(eq("custody"), any())).thenReturn("https://cdn.invalid/p.jpg");

        mvc.perform(post("/driver/evidence/presign")
                        .with(authentication(driverAuth())).with(csrf())
                        .param("contentType", "image/jpeg"))
                .andExpect(status().isOk())
                // The bytes go straight to storage; this response is a pair of
                // URLs and nothing that belongs in a cache.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));

        // The key carries the driver's own id and a random one. A key naming a
        // shipment would let somebody who knew the scheme walk the bucket for
        // pictures of other people's doorways.
        verify(storage).presignUpload(eq("custody"),
                org.mockito.ArgumentMatchers.startsWith("custody-950-"), eq("image/jpeg"), any());
    }

    @Test
    void anEvidenceUploadThatIsNotAPictureIsRefusedBeforeStorageIsAsked() throws Exception {
        // An allow-list, so a driver's app cannot presign a URL for an HTML page
        // and have it served back from the platform's own domain.
        mvc.perform(post("/driver/evidence/presign")
                        .with(authentication(driverAuth())).with(csrf())
                        .param("contentType", "text/html"))
                .andExpect(status().isBadRequest());

        verify(storage, never()).presignUpload(any(), any(), any(), any());
    }

    // ── What the responses say about themselves ──────────────────────────────

    @Test
    void aParcelIsNeverAllowedIntoACache() throws Exception {
        when(custody.shipment(950L, 11L)).thenReturn(new DriverResponses.ShipmentDetail(
                11L, "SHP-1", ShipmentStatus.OUT_FOR_DELIVERY, 1, "1 item(s)",
                null, null, List.of(), List.of(), 0, null, true, null, null));

        mvc.perform(get("/driver/shipments/11").with(authentication(driverAuth())))
                .andExpect(status().isOk())
                // This response carries a recipient's home address. A phone in
                // a shared vehicle would hold it for whoever picks it up next.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("private")));
    }

    @Test
    void norAreEarningsOrHistory() throws Exception {
        when(profiles.earnings(any(), any(), any())).thenReturn(
                new DriverResponses.Earnings(null, null, List.of(), 0, null));
        when(profiles.history(any(), any())).thenReturn(
                new DriverResponses.History(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/driver/earnings").with(authentication(driverAuth())))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/driver/history").with(authentication(driverAuth())))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DriverResponses.CustodyRecorded recorded() {
        return new DriverResponses.CustodyRecorded(1L,
                com.sujula.model.constant.CustodyEventType.COLLECTED, 11L,
                ShipmentStatus.OUT_FOR_DELIVERY, LocalDateTime.now(), true, null, false, "ok");
    }

    private static org.springframework.security.core.Authentication driverAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                driver(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_DELIVERY")));
    }

    private static com.sujula.model.user.User driver() {
        com.sujula.model.user.User user = new com.sujula.model.user.User();
        user.setId(950L);
        user.setEmail("ebrima@sujula.gm");
        user.setRole(com.sujula.model.constant.UserRole.DELIVERY);
        return user;
    }
}
