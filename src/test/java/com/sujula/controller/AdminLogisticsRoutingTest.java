package com.sujula.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminLogisticsResponses;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.service.admin.AdminLogisticsService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may change the shape of the delivery network.
 *
 * <p>Support reads and an administrator decides, all the way across this
 * surface. A path rule cannot express that — the read and the write sit under
 * the same prefix — so it is per endpoint, and this is what would notice an
 * endpoint that called {@code staff} where it meant {@code decider}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminLogisticsRoutingTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private AdminLogisticsService logistics;

    @Test
    void theLogisticsSurfaceIsNotOpen() throws Exception {
        // 403 rather than 401, as everywhere else in this application.
        mvc.perform(get("/admin/drivers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/zones")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/rate-cards")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/pickup-points")).andExpect(status().isUnauthorized());

        verify(logistics, never()).listDrivers(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDriverCannotReadTheDriverList() throws Exception {
        // The obvious mistake: DELIVERY is a role on this platform, and a driver
        // reading every other driver's acceptance score and position is not a
        // thing this surface is for.
        mvc.perform(get("/admin/drivers").with(authentication(auth(800L, UserRole.DELIVERY))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/pickup-points")
                        .with(authentication(auth(801L, UserRole.PICKUP_OPERATOR))))
                .andExpect(status().isForbidden());

        verify(logistics, never()).listDrivers(any(), any(), any(), any(), any(), any());
    }

    @Test
    void supportCanReadDriversCountersZonesAndCards() throws Exception {
        when(logistics.listDrivers(any(), any(), any(), any(), any(), any()))
                .thenReturn(empty());
        when(logistics.listPickupPoints(any(), any(), any(), any(), any(), any()))
                .thenReturn(empty());
        when(logistics.listZones(any(), any(), any(), any())).thenReturn(empty());
        when(logistics.listRateCards(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(empty());
        when(logistics.previewRates(any(), any())).thenReturn(List.of());

        Authentication support = auth(810L, UserRole.SUPPORT);
        mvc.perform(get("/admin/drivers").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/pickup-points").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/zones").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/rate-cards").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/rate-cards/preview").with(authentication(support)))
                .andExpect(status().isOk());
    }

    @Test
    void supportCannotApproveADriverOrDrawAZoneOrWriteACard() throws Exception {
        Authentication support = auth(811L, UserRole.SUPPORT);

        mvc.perform(post("/admin/drivers/5/approve").with(authentication(support)).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/admin/drivers/5/zones").with(authentication(support)).with(csrf())
                        .contentType("application/json").content("{\"zoneCodes\":[]}"))
                .andExpect(status().isForbidden());
        // Bodies that bind cleanly, so what refuses these is the role rather
        // than the validator — otherwise the test would pass with the
        // authorisation check deleted.
        mvc.perform(post("/admin/zones").with(authentication(support)).with(csrf())
                        .contentType("application/json").content("""
                                {"code":"GM-SRK","name":"Serrekunda","countryCode":"GM",
                                 "geometry":"{\\"type\\":\\"Polygon\\",\\"coordinates\\":[]}"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/rate-cards").with(authentication(support)).with(csrf())
                        .contentType("application/json").content("""
                                {"name":"Kombo standard","countryCode":"GM","currency":"GMD",
                                 "baseFee":50.00}"""))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/pickup-points/3/suspend").with(authentication(support)).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        verify(logistics, never()).approveDriver(any(), any(), any());
        verify(logistics, never()).createZone(any(), any());
        verify(logistics, never()).createRateCard(any(), any());
        verify(logistics, never()).suspendPickupPoint(any(), any(), any());
    }

    @Test
    void aZoneWithNoPolygonNeverReachesTheService() throws Exception {
        // The binder refuses it, because a zone without a shape is not a zone —
        // it is a name that contains nothing and quietly delivers nowhere.
        mvc.perform(post("/admin/zones").with(authentication(auth(812L, UserRole.ADMIN)))
                        .with(csrf()).contentType("application/json")
                        .content("""
                                {"code":"GM-SRK","name":"Serrekunda","countryCode":"GM"}"""))
                .andExpect(status().isBadRequest());

        verify(logistics, never()).createZone(any(), any());
    }

    @Test
    void aSuspensionWithNoReasonNeverReachesTheService() throws Exception {
        // The operator is shown this. An empty suspension reason is a shop told
        // it has been shut down and not told why.
        mvc.perform(post("/admin/pickup-points/3/suspend")
                        .with(authentication(auth(813L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(logistics, never()).suspendPickupPoint(any(), any(), any());
    }

    @Test
    void aCounterWithNoPinNeverReachesTheService() throws Exception {
        // Most addresses in this market have no postal code and many have no
        // street number. The pin is the address, and a counter without one
        // cannot be routed to.
        mvc.perform(post("/admin/pickup-points")
                        .with(authentication(auth(814L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"name":"Kairaba Pharmacy","addressStreet":"Kairaba Avenue",
                                 "city":"Serrekunda","countryCode":"GM"}"""))
                .andExpect(status().isBadRequest());

        verify(logistics, never()).createPickupPoint(any(), any());
    }

    private static <T> PagedResponse<T> empty() {
        return PagedResponse.<T>builder()
                .content(List.of()).page(0).size(20).totalElements(0).totalPages(0).last(true)
                .build();
    }

    private static Authentication auth(Long userId, UserRole role) {
        User user = new User();
        user.setId(userId);
        user.setEmail("user" + userId + "@sujula.gm");
        user.setRole(role);
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }
}
