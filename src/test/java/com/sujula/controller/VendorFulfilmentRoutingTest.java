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

import com.sujula.dto.request.fulfilment.FulfilmentRequests;
import com.sujula.dto.response.fulfilment.FulfilmentResponses;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.service.VendorOrderService;
import com.sujula.service.fulfilment.VendorFulfilmentService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who reaches the fulfilment desk, which handler answers, and what the response
 * is allowed to say about itself.
 *
 * <p>The routing half matters here for a specific reason:
 * {@code /vendor/orders/{id}} and {@code /vendor/orders/stats} are the same
 * shape, and {@code /vendor/orders/{id}/handoff-code} and
 * {@code /vendor/orders/{id}/handoff-code/regenerate} differ by one segment. The
 * only way to know which pattern a booted dispatcher picks is to ask one.
 *
 * <p>The caching half is not paperwork either. The collection code releases a
 * parcel, and a code sitting in a proxy or a browser's back-forward cache is a
 * code that releases somebody else's goods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VendorFulfilmentRoutingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VendorFulfilmentService fulfilment;

    @MockitoBean
    private VendorOrderService vendorOrders;

    // ── Nothing here is open ─────────────────────────────────────────────────

    @Test
    void everyFulfilmentRouteRefusesAnAnonymousCaller() throws Exception {
        mvc.perform(get("/vendor/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/orders/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/orders/1/accept")).andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/orders/1/reject")
                .contentType("application/json").content("{\"reason\":\"none\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/orders/1/ready")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/orders/1/handoff-code")).andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/orders/1/handoff-code/regenerate")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/orders/1/label")).andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/orders/1/lines/2/assign-imei")
                .contentType("application/json").content("{\"imei\":\"356938035643809\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void andTheServiceIsNeverReached() throws Exception {
        mvc.perform(get("/vendor/orders/1/handoff-code"));
        mvc.perform(get("/vendor/orders/1/label"));
        mvc.perform(post("/vendor/orders/1/accept"));

        // Refused by the chain rather than by a check inside the service. A code
        // that releases a parcel must not depend on a service remembering to
        // look at who is asking.
        verify(fulfilment, never()).releaseCode(any(), any());
        verify(fulfilment, never()).label(any(), any());
        verify(fulfilment, never()).accept(any(), any());
    }

    // ── The right handler, with the right arguments ──────────────────────────

    @Test
    void acceptReachesAcceptWithTheCallersOwnId() throws Exception {
        when(fulfilment.accept(950L, 11L)).thenReturn(new FulfilmentResponses.Accepted(
                11L, "SJL-1", VendorOrderStatus.PREPARING, LocalDateTime.now(), "ok"));

        mvc.perform(post("/vendor/orders/11/accept").with(authentication(sellerAuth())).with(csrf()))
                .andExpect(status().isOk());

        // 950 comes from the principal, not from the path. There is no id in
        // the URL a caller could change to act as somebody else.
        verify(fulfilment).accept(950L, 11L);
    }

    @Test
    void rejectBindsTheReasonFromTheBody() throws Exception {
        when(fulfilment.reject(eq(950L), eq(11L), any())).thenReturn(new FulfilmentResponses.Rejected(
                11L, "SJL-1", VendorOrderStatus.CANCELLED, LocalDateTime.now(),
                "Damaged", "REF-1", false, 1, "ok"));

        mvc.perform(post("/vendor/orders/11/reject")
                        .with(authentication(sellerAuth())).with(csrf())
                        .contentType("application/json")
                        .content("{\"reason\":\"The last one was damaged\"}"))
                .andExpect(status().isOk());

        verify(fulfilment).reject(950L, 11L,
                new FulfilmentRequests.Reject("The last one was damaged"));
    }

    @Test
    void aRejectionWithNoReasonIsRefusedBeforeTheServiceSeesIt() throws Exception {
        mvc.perform(post("/vendor/orders/11/reject")
                        .with(authentication(sellerAuth())).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(fulfilment, never()).reject(any(), any(), any());
    }

    @Test
    void theRegenerateRouteWinsOverTheCodeRoute() throws Exception {
        // One extra path segment apart. If the shorter pattern swallowed the
        // longer one, reissuing would silently return the code it was meant to
        // replace — and the driver would be told a code that no longer works.
        when(fulfilment.regenerateReleaseCode(950L, 11L)).thenReturn(code("222222", true));

        mvc.perform(post("/vendor/orders/11/handoff-code/regenerate")
                        .with(authentication(sellerAuth())).with(csrf()))
                .andExpect(status().isOk());

        verify(fulfilment).regenerateReleaseCode(950L, 11L);
        verify(fulfilment, never()).releaseCode(any(), any());
    }

    @Test
    void statsIsNotReadAsAnOrderId() throws Exception {
        // Same shape as /vendor/orders/{id}. If the variable pattern won, the
        // dashboard would answer "order 'stats' was not found".
        mvc.perform(get("/vendor/orders/stats").with(authentication(sellerAuth())));

        verify(vendorOrders).stats(950L);
        verify(vendorOrders, never()).findMyOrder(any(), any());
    }

    @Test
    void assignImeiCarriesBothThePathIds() throws Exception {
        when(fulfilment.assignImei(eq(950L), eq(11L), eq(77L), any()))
                .thenReturn(new FulfilmentResponses.ImeiAssigned(11L, 77L, "356938035643809",
                        1, 1, true, List.of("356938035643809"), true, "ok"));

        mvc.perform(post("/vendor/orders/11/lines/77/assign-imei")
                        .with(authentication(sellerAuth())).with(csrf())
                        .contentType("application/json")
                        .content("{\"imei\":\"356938035643809\"}"))
                .andExpect(status().isOk());

        verify(fulfilment).assignImei(950L, 11L, 77L,
                new FulfilmentRequests.AssignImei("356938035643809"));
    }

    @Test
    void anImeiOfTheWrongShapeNeverReachesTheService() throws Exception {
        mvc.perform(post("/vendor/orders/11/lines/77/assign-imei")
                        .with(authentication(sellerAuth())).with(csrf())
                        .contentType("application/json").content("{\"imei\":\"12345\"}"))
                .andExpect(status().isBadRequest());

        verify(fulfilment, never()).assignImei(any(), any(), any(), any());
    }

    // ── What the response says about itself ──────────────────────────────────

    @Test
    void theCollectionCodeIsNeverAllowedIntoACache() throws Exception {
        when(fulfilment.releaseCode(950L, 11L)).thenReturn(code("123456", false));

        mvc.perform(get("/vendor/orders/11/handoff-code").with(authentication(sellerAuth())))
                .andExpect(status().isOk())
                // no-store rather than no-cache: the second still permits a copy
                // on disk, and this six-digit number opens a parcel.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("private")));
    }

    @Test
    void soIsTheOneHandedBackWhenAnOrderIsPacked() throws Exception {
        when(fulfilment.ready(950L, 11L)).thenReturn(new FulfilmentResponses.Ready(
                11L, "SJL-1", VendorOrderStatus.READY_FOR_PICKUP, LocalDateTime.now(),
                code("123456", false), "ok"));

        mvc.perform(post("/vendor/orders/11/ready")
                        .with(authentication(sellerAuth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void theLabelIsServedInlineAsAPdfAndNotCached() throws Exception {
        when(fulfilment.label(950L, 11L)).thenReturn(new VendorFulfilmentService.ParcelLabel(
                new byte[] {1, 2, 3}, "parcel-SJL-1-11.pdf", "application/pdf"));

        mvc.perform(get("/vendor/orders/11/label").with(authentication(sellerAuth())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("parcel-SJL-1-11.pdf")))
                // A label names a recipient and a town. It has no business in a
                // shared cache.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    /**
     * The vendor surface demands a CSRF token on writes.
     *
     * <p>Pinned deliberately, because until this test nothing exercised it: every
     * other vendor test POSTs anonymously and asserts 403, which passes whether
     * the refusal came from authentication or from CSRF. So the posture was
     * never actually verified, and a change to it would have gone unnoticed.
     *
     * <p>Worth knowing rather than assuming: {@code /auth} and {@code /me} are
     * exempt on the stated grounds that a bearer token is never attached by a
     * browser on its own. This surface is reached by the same bearer-token
     * filter but is not exempt, so a seller's client must read the XSRF-TOKEN
     * cookie and echo it. That works for a browser console and does not for a
     * native app with no cookie jar.
     */
    @Test
    void anAuthenticatedWriteWithoutACsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/vendor/orders/11/accept").with(authentication(sellerAuth())))
                .andExpect(status().isForbidden());

        verify(fulfilment, never()).accept(any(), any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static FulfilmentResponses.ReleaseCode code(String value, boolean reissued) {
        return new FulfilmentResponses.ReleaseCode(value, LocalDateTime.now(),
                LocalDateTime.now().plusDays(3), 1, reissued, "ok");
    }

    /**
     * The principal this API actually carries.
     *
     * <p>{@code AuthenticatedCaller} reads the user off the authentication's
     * principal rather than a username, so a plain {@code user("lamin")} would
     * authenticate the request and then fail inside the controller for a reason
     * that has nothing to do with what is being tested.
     */
    private static org.springframework.security.core.Authentication sellerAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                seller(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_VENDOR")));
    }

    private static com.sujula.model.user.User seller() {
        com.sujula.model.user.User user = new com.sujula.model.user.User();
        user.setId(950L);
        user.setEmail("lamin@sujula.gm");
        user.setRole(com.sujula.model.constant.UserRole.VENDOR);
        return user;
    }
}
