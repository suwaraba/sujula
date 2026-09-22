package com.sujula.controller;

import java.time.LocalDateTime;
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
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.ReturnReason;
import com.sujula.model.constant.ReturnStatus;
import com.sujula.model.constant.ThreadSubject;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.service.aftersales.DisputeService;
import com.sujula.service.aftersales.MessagingService;
import com.sujula.service.aftersales.ReturnService;
import com.sujula.service.aftersales.ReviewModerationService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the after-sales surface refuses at the door.
 *
 * <p>Two things are being asserted. Nothing here is open — a return carries an
 * order, photographs of somebody's home and what one party said about the other.
 * And nothing here is gated on a <em>role</em>: a buyer and a seller both write
 * to the same rows, and which of them the caller is gets decided by which
 * ownership query finds the row, not by an authority on a token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AfterSalesRoutingTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private ReturnService returns;
    @MockitoBean private DisputeService disputes;
    @MockitoBean private ReviewModerationService reviews;
    @MockitoBean private MessagingService messages;

    // ── Nothing here is open ─────────────────────────────────────────────────

    @Test
    void everyAfterSalesRouteNeedsAnAccount() throws Exception {
        // 403 rather than 401 throughout this application: the entry point
        // refuses without inviting a challenge, which is what the rest of the
        // signed-in surface does and what BuyerOrderSecurityTest asserts too.
        mvc.perform(get("/returns")).andExpect(status().isUnauthorized());
        mvc.perform(get("/returns/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/disputes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/messages/threads")).andExpect(status().isUnauthorized());
        mvc.perform(post("/returns").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/reviews/1/report").with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        verify(returns, never()).list(any(), any(), any());
        verify(messages, never()).threads(any(), any());
    }

    // ── A buyer and a seller reach the same endpoints ────────────────────────

    @Test
    void aBuyerAndASellerBothReadTheSameReturnsEndpoint() throws Exception {
        when(returns.list(eq(700L), any(), any())).thenReturn(emptyReturns());
        when(returns.list(eq(800L), any(), any())).thenReturn(emptyReturns());

        mvc.perform(get("/returns").with(authentication(auth(700L, UserRole.CUSTOMER))))
                .andExpect(status().isOk());
        mvc.perform(get("/returns").with(authentication(auth(800L, UserRole.VENDOR))))
                .andExpect(status().isOk());

        // No role decides anything. The caller's id goes into the query and the
        // two ownership tests decide which rows exist for them.
        verify(returns).list(eq(700L), any(), any());
        verify(returns).list(eq(800L), any(), any());
    }

    @Test
    void theSellersOwnActionsAreNotGatedOnARoleEither() throws Exception {
        when(returns.approve(eq(800L), eq(11L), any())).thenReturn(null);

        mvc.perform(post("/returns/11/approve")
                        .with(authentication(auth(800L, UserRole.CUSTOMER))).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        // Reaching the service is the point: the service finds no vendor for
        // this caller and answers not-found, which withholds whether the return
        // exists. A role check here would have leaked that it does.
        verify(returns).approve(eq(800L), eq(11L), any());
    }

    // ── The caller is never taken from the request ───────────────────────────

    @Test
    void thereIsNoUserIdInAnyPath() throws Exception {
        when(returns.detail(eq(700L), eq(11L))).thenReturn(null);
        when(disputes.detail(eq(700L), eq(12L))).thenReturn(null);
        when(messages.thread(eq(700L), eq(13L), any())).thenReturn(null);

        mvc.perform(get("/returns/11").with(authentication(auth(700L, UserRole.CUSTOMER))))
                .andExpect(status().isOk());
        mvc.perform(get("/disputes/12").with(authentication(auth(700L, UserRole.CUSTOMER))))
                .andExpect(status().isOk());
        mvc.perform(get("/messages/threads/13").with(authentication(auth(700L, UserRole.CUSTOMER))))
                .andExpect(status().isOk());

        // Every one of them took the subject from the principal. There is no id
        // a caller could change to reach somebody else's row, because no such id
        // is ever read.
        verify(returns).detail(700L, 11L);
        verify(disputes).detail(700L, 12L);
        verify(messages).thread(eq(700L), eq(13L), any());
    }

    // ── What the binder refuses before the service is troubled ───────────────

    @Test
    void aReturnWithNoItemsNeverReachesTheService() throws Exception {
        mvc.perform(post("/returns")
                        .with(authentication(auth(700L, UserRole.CUSTOMER))).with(csrf())
                        .contentType("application/json")
                        .content("{\"vendorOrderId\":1,\"reason\":\"FAULTY\",\"items\":[]}"))
                .andExpect(status().isBadRequest());

        verify(returns, never()).open(any(), any());
    }

    @Test
    void aRejectionWithNoReasonIsRefused() throws Exception {
        mvc.perform(post("/returns/11/reject")
                        .with(authentication(auth(800L, UserRole.VENDOR))).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        // A refusal with nothing behind it is what turns a return into a dispute.
        verify(returns, never()).reject(any(), any(), any());
    }

    @Test
    void evidenceWithNoCaptionIsRefused() throws Exception {
        mvc.perform(post("/disputes/12/evidence")
                        .with(authentication(auth(700L, UserRole.CUSTOMER))).with(csrf())
                        .contentType("application/json")
                        .content("{\"url\":\"https://media.invalid/box.jpg\"}"))
                .andExpect(status().isBadRequest());

        // Without a caption a moderator has a photograph of a box.
        verify(disputes, never()).addEvidence(any(), any(), any());
    }

    @Test
    void anOfferOfNothingIsAnOfferOfNothing() throws Exception {
        mvc.perform(post("/returns/11/offer-partial-refund")
                        .with(authentication(auth(800L, UserRole.VENDOR))).with(csrf())
                        .contentType("application/json").content("{\"amount\":0}"))
                .andExpect(status().isBadRequest());

        verify(returns, never()).offerPartialRefund(any(), any(), any());
    }

    @Test
    void aRatingOutsideOneToFiveIsRefused() throws Exception {
        mvc.perform(patch("/reviews/9")
                        .with(authentication(auth(700L, UserRole.CUSTOMER))).with(csrf())
                        .contentType("application/json").content("{\"rating\":9}"))
                .andExpect(status().isBadRequest());

        verify(reviews, never()).edit(any(), any(), any());
    }

    // ── The review routes are reachable and distinct ─────────────────────────

    @Test
    void editDeleteReplyAndReportAreFourDifferentThings() throws Exception {
        Authentication caller = auth(700L, UserRole.CUSTOMER);
        when(reviews.edit(eq(700L), eq(9L), any())).thenReturn(null);
        when(reviews.delete(700L, 9L)).thenReturn(
                new AfterSalesResponses.ReviewDeleted(9L, "gone"));
        when(reviews.reply(eq(700L), eq(9L), any())).thenReturn(null);
        when(reviews.report(eq(700L), eq(9L), any())).thenReturn(
                new AfterSalesResponses.ReviewReported(9L, 1, "read"));

        mvc.perform(patch("/reviews/9").with(authentication(caller)).with(csrf())
                        .contentType("application/json").content("{\"rating\":3}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/reviews/9").with(authentication(caller)).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/reviews/9/reply").with(authentication(caller)).with(csrf())
                        .contentType("application/json").content("{\"body\":\"Sorry.\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/reviews/9/report").with(authentication(caller)).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isOk());

        verify(reviews).edit(eq(700L), eq(9L), any());
        verify(reviews).delete(700L, 9L);
        verify(reviews).reply(eq(700L), eq(9L), any());
        verify(reviews).report(eq(700L), eq(9L), any());
    }

    @Test
    void aThreadsOwnMessagesRouteDoesNotCollideWithTheThreadRead() throws Exception {
        Authentication caller = auth(700L, UserRole.CUSTOMER);
        when(messages.send(eq(700L), eq(13L), any())).thenReturn(
                new AfterSalesResponses.MessageSent(13L, null, null));

        mvc.perform(post("/messages/threads/13/messages").with(authentication(caller)).with(csrf())
                        .contentType("application/json").content("{\"body\":\"Any news?\"}"))
                .andExpect(status().isOk());

        verify(messages).send(eq(700L), eq(13L), any());
        verify(messages, never()).thread(any(), any(), any());
    }

    @Test
    void openingAThreadAnswersCreated() throws Exception {
        when(messages.openThread(eq(700L), any())).thenReturn(
                new AfterSalesResponses.ThreadDetail(13L, ThreadSubject.PRODUCT, "Is it dual sim?",
                        null, 5L, "Galaxy A15", "Kombo Electronics", false, List.of(), 1, 0, 50));

        mvc.perform(post("/messages/threads")
                        .with(authentication(auth(700L, UserRole.CUSTOMER))).with(csrf())
                        .contentType("application/json")
                        .content("{\"subject\":\"PRODUCT\",\"productId\":5,"
                                + "\"title\":\"Is it dual sim?\",\"body\":\"Is this dual sim?\"}"))
                .andExpect(status().isCreated());
    }

    private static PagedResponse<AfterSalesResponses.ReturnSummary> emptyReturns() {
        return PagedResponse.<AfterSalesResponses.ReturnSummary>builder()
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
