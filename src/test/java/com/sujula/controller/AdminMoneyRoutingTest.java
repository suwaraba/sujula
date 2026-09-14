package com.sujula.controller;

import java.math.BigDecimal;
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
import com.sujula.dto.response.admin.AdminMoneyResponses;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.service.admin.AdminMoneyService;

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
 * Who may see the platform's money, and who may move it.
 *
 * <p>Support reads all of it — an agent on the telephone to somebody whose
 * refund has not arrived needs the payment and the ledger rows behind it — and
 * writes nothing. A seller reaching any of this would be reading every other
 * seller's earnings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminMoneyRoutingTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private AdminMoneyService money;

    @Test
    void theMoneySurfaceIsNotOpen() throws Exception {
        mvc.perform(get("/admin/payments")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/ledger")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/balances")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/payouts/batches")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/fx/rates")).andExpect(status().isForbidden());

        verify(money, never()).listPayments(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aSellerCannotReadEveryOtherSellersEarnings() throws Exception {
        mvc.perform(get("/admin/balances").with(authentication(auth(900L, UserRole.VENDOR))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/ledger").with(authentication(auth(901L, UserRole.VENDOR))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/reports/revenue")
                        .with(authentication(auth(902L, UserRole.VENDOR))))
                .andExpect(status().isForbidden());

        verify(money, never()).listBalances(any(), any(), anyBoolean(), any());
    }

    @Test
    void supportReadsPaymentsTheLedgerAndTheReconciliation() throws Exception {
        when(money.listPayments(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyPage());
        when(money.exploreLedger(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AdminMoneyResponses.LedgerPage(List.of(), List.of(),
                        0, 50, 0, 0, true));
        when(money.reconcile(any())).thenReturn(new AdminMoneyResponses.Reconciliation(
                java.time.LocalDate.now(), List.of(), List.of(), "Nothing unaccounted for."));
        when(money.listBalances(any(), any(), anyBoolean(), any())).thenReturn(emptyPage());
        when(money.revenue(any(), any(), any())).thenReturn(
                new AdminMoneyResponses.RevenueReport(java.time.LocalDate.now(),
                        java.time.LocalDate.now(), List.of(), "Nothing sold."));

        Authentication support = auth(910L, UserRole.SUPPORT);
        mvc.perform(get("/admin/payments").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/ledger").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/ledger/reconciliation").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/balances").with(authentication(support)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/reports/revenue").with(authentication(support)))
                .andExpect(status().isOk());
    }

    @Test
    void supportMovesNoMoney() throws Exception {
        Authentication support = auth(911L, UserRole.SUPPORT);

        mvc.perform(post("/admin/payments/5/refund").with(authentication(support)).with(csrf())
                        .contentType("application/json")
                        .content("{\"vendorOrderId\":7,\"reason\":\"Cracked screen.\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/payouts/batches").with(authentication(support)).with(csrf())
                        .contentType("application/json")
                        .content("{\"currency\":\"GMD\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/payouts/batches/3/approve").with(authentication(support))
                        .with(csrf()).contentType("application/json")
                        .content("{\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/admin/fx/spread").with(authentication(support)).with(csrf())
                        .contentType("application/json")
                        .content("{\"basisPoints\":150,\"reason\":\"Volatility.\"}"))
                .andExpect(status().isForbidden());

        verify(money, never()).refund(any(), any(), any());
        verify(money, never()).prepareBatch(any(), any());
        verify(money, never()).approveBatch(any(), any(), any());
        verify(money, never()).setSpread(any(), any());
    }

    @Test
    void aRefundWithNoSubOrderNeverReachesTheService() throws Exception {
        // The binder refuses it, because a refund without a sub-order is a
        // refund of a proportion, and a payment split between three sellers has
        // no meaningful proportion (C3).
        mvc.perform(post("/admin/payments/5/refund")
                        .with(authentication(auth(912L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json")
                        .content("{\"reason\":\"Cracked screen.\"}"))
                .andExpect(status().isBadRequest());

        verify(money, never()).refund(any(), any(), any());
    }

    @Test
    void aRefundWithNoReasonNeverReachesTheService() throws Exception {
        // The buyer and the seller are both shown it.
        mvc.perform(post("/admin/payments/5/refund")
                        .with(authentication(auth(913L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json")
                        .content("{\"vendorOrderId\":7,\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(money, never()).refund(any(), any(), any());
    }

    @Test
    void aSpreadOfOneHundredAndFiftyPercentNeverReachesTheService() throws Exception {
        // 15000 basis points is 150%. The failure mode this guards is somebody
        // typing a percentage into a field that wanted basis points, and it
        // would be charged to real buyers before anybody noticed.
        mvc.perform(patch("/admin/fx/spread")
                        .with(authentication(auth(914L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json")
                        .content("{\"basisPoints\":15000,\"reason\":\"Oops.\"}"))
                .andExpect(status().isBadRequest());

        verify(money, never()).setSpread(any(), any());
    }

    @Test
    void assemblingAPayoutRunWithoutAPasswordNeverReachesTheService() throws Exception {
        mvc.perform(post("/admin/payouts/batches")
                        .with(authentication(auth(915L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json")
                        .content("{\"currency\":\"GMD\"}"))
                .andExpect(status().isBadRequest());

        verify(money, never()).prepareBatch(any(), any());
    }

    @Test
    void anExportOfAnUnknownTypeIsRefusedByThePathRatherThanRunAsATypedQuery() throws Exception {
        // The path segment decides which query runs and which rows leave the
        // building. "Whatever the client typed" is not an access-control
        // decision, which is why the type is an enum.
        mvc.perform(post("/admin/reports/EVERYTHING/export")
                        .with(authentication(auth(916L, UserRole.ADMIN))).with(csrf())
                        .contentType("application/json").content("{\"type\":\"LEDGER\"}"))
                .andExpect(status().isBadRequest());

        verify(money, never()).requestExport(any(), any(), any());
    }

    private static <T> PagedResponse<T> emptyPage() {
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
