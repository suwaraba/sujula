package com.sujula.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sujula.dto.response.analytics.AnalyticsResponses;
import com.sujula.dto.response.money.MoneyResponses;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.service.analytics.VendorAnalyticsService;
import com.sujula.service.money.VendorMoneyService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * Who reaches a shop's figures, and what the responses say about themselves.
 *
 * <p>What sits behind these routes is worth being explicit about: a shop's
 * revenue, its margins, a bank-ready statement of its income, and the list of
 * countries it ships to. An unguarded route here hands a competitor the whole
 * trading position of a business.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VendorAnalyticsRoutingTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VendorAnalyticsService analytics;

    @MockitoBean
    private VendorMoneyService money;

    // ── Nothing here is open ─────────────────────────────────────────────────

    @Test
    void everyAnalyticsAndMoneyRouteRefusesAnAnonymousCaller() throws Exception {
        mvc.perform(get("/vendor/analytics/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/analytics/sales")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/analytics/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/analytics/customers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/analytics/delivery")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/balance")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/transactions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/payouts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/vendor/payouts/request")).andExpect(status().isUnauthorized());
        mvc.perform(get("/vendor/statements/2026-09")).andExpect(status().isUnauthorized());
    }

    @Test
    void andNeitherServiceIsEverReached() throws Exception {
        mvc.perform(get("/vendor/balance"));
        mvc.perform(get("/vendor/statements/2026-09"));
        mvc.perform(get("/vendor/analytics/customers"));

        // Refused by the chain rather than by a check inside a service. A
        // statement of somebody's income must not depend on a service
        // remembering to look at who is asking.
        verify(money, never()).balance(any());
        verify(money, never()).statement(any(), any(), any(), any());
        verify(analytics, never()).customers(any(), any(), any());
    }

    // ── The right handler, with the right arguments ──────────────────────────

    @Test
    void overviewReachesOverviewWithTheCallersOwnIdAndTheDatesAsked() throws Exception {
        when(analytics.overview(eq(950L), any(), any())).thenReturn(overview());

        mvc.perform(get("/vendor/analytics/overview")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(authentication(sellerAuth())))
                .andExpect(status().isOk());

        // 950 comes from the principal. There is no id in the URL a caller
        // could change to read another shop's takings.
        verify(analytics).overview(950L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void datesAreOptionalAndArriveAsNullRatherThanADefaultInvented() throws Exception {
        when(analytics.sales(eq(950L), any(), any(), any())).thenReturn(
                new AnalyticsResponses.Sales(period(), "day", List.of(), null));

        mvc.perform(get("/vendor/analytics/sales").with(authentication(sellerAuth())))
                .andExpect(status().isOk());

        // The service picks the default window, so there is one place that
        // decides it rather than two that drift.
        verify(analytics).sales(950L, null, null, "day");
    }

    @Test
    void statementsBindsThePeriodFromThePathAndTheFormatFromTheQuery() throws Exception {
        when(money.statement(eq(950L), eq("2026-09"), eq("GMD"), eq("csv")))
                .thenReturn(new MoneyResponses.Statement("a,b\n".getBytes(), "s.csv", "text/csv"));

        mvc.perform(get("/vendor/statements/2026-09")
                        .param("currency", "GMD").param("format", "csv")
                        .with(authentication(sellerAuth())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")));

        verify(money).statement(950L, "2026-09", "GMD", "csv");
    }

    @Test
    void aPayoutRequestWorksWithNoBodyAtAll() throws Exception {
        when(money.requestPayout(eq(950L), any())).thenReturn(new MoneyResponses.PayoutRequested(
                1L, "PAY-1", PayoutStatus.REQUESTED, java.math.BigDecimal.TEN, "GMD",
                LocalDateTime.now(), "ok"));

        // There is nothing a seller has to send: the request is for whatever is
        // available, so an empty POST is the ordinary case.
        mvc.perform(post("/vendor/payouts/request")
                        .with(authentication(sellerAuth())).with(csrf()))
                .andExpect(status().isOk());

        verify(money).requestPayout(eq(950L), any());
    }

    @Test
    void aTransactionFilterBindsTheEntryType() throws Exception {
        when(money.transactions(eq(950L), any(), any(), any(), any(), any()))
                .thenReturn(new MoneyResponses.Transactions(List.of(), 0, 50, 0, 0, null));

        mvc.perform(get("/vendor/transactions")
                        .param("type", "COMMISSION").param("currency", "GMD")
                        .with(authentication(sellerAuth())))
                .andExpect(status().isOk());

        verify(money).transactions(eq(950L), eq("GMD"), eq(LedgerEntryType.COMMISSION),
                isNull(), isNull(), any());
    }

    @Test
    void anUnknownEntryTypeIsRefusedBeforeTheServiceSeesIt() throws Exception {
        mvc.perform(get("/vendor/transactions").param("type", "EMBEZZLEMENT")
                        .with(authentication(sellerAuth())))
                .andExpect(status().isBadRequest());

        verify(money, never()).transactions(any(), any(), any(), any(), any(), any());
    }

    // ── What the responses say about themselves ──────────────────────────────

    @Test
    void aBalanceIsNeverAllowedIntoACache() throws Exception {
        when(money.balance(950L)).thenReturn(new MoneyResponses.Balance(List.of(),
                LocalDateTime.now(), null));

        mvc.perform(get("/vendor/balance").with(authentication(sellerAuth())))
                .andExpect(status().isOk())
                // A figure cached on a shared machine is somebody's income left
                // for the next person, and a stale one is worse than none
                // because it will be believed.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("private")));
    }

    @Test
    void norIsAStatement() throws Exception {
        when(money.statement(any(), any(), any(), any()))
                .thenReturn(new MoneyResponses.Statement(new byte[] {1}, "s.pdf", "application/pdf"));

        mvc.perform(get("/vendor/statements/2026-09").with(authentication(sellerAuth())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static AnalyticsResponses.Period period() {
        return new AnalyticsResponses.Period(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 29);
    }

    private static AnalyticsResponses.Overview overview() {
        return new AnalyticsResponses.Overview(period(), period(), List.of(),
                0, 0, null, 0, 0, 0L, null, null, "basis", 0, 0, null);
    }

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
