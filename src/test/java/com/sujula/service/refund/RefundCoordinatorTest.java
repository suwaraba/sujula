package com.sujula.service.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Payment;
import com.sujula.service.payment.PaymentGateway;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;

class RefundCoordinatorTest {

    private RefundTransactionService transactions;
    private IdempotentGateway gateway;
    private RefundCoordinator coordinator;
    private RefundCoordinator.RefundCommand command;
    private RefundCoordinator.PreparedRefund prepared;
    private RefundCoordinator.RefundResult completed;

    @BeforeEach
    void setUp() {
        transactions = mock(RefundTransactionService.class);
        gateway = new IdempotentGateway();
        coordinator = new RefundCoordinator(transactions, List.of(gateway),
                CurrencyCatalogue.of(new ReferenceDataProperties()));
        Payment payment = Payment.builder()
                .id(8L).reference("PAY-8").method(PaymentMethod.CARD)
                .status(PaymentStatus.PAID).amount(new BigDecimal("100.00"))
                .amountRefunded(BigDecimal.ZERO).currency("EUR")
                .transactionId("pi_8").build();
        command = new RefundCoordinator.RefundCommand(null, 8L, 18L,
                new BigDecimal("25.00"), new BigDecimal("250.00"), false,
                "Damaged", null, null);
        completed = new RefundCoordinator.RefundResult(41L, "RFD-41", 18L, "Store",
                new BigDecimal("25.00"), "EUR", new BigDecimal("250.00"), "GMD",
                new BigDecimal("0.10000000"), null, false, false);
        prepared = new RefundCoordinator.PreparedRefund(41L, payment,
                new BigDecimal("25.00"), "EUR", new BigDecimal("250.00"), "GMD",
                "Damaged", "refund-request-41", null);
    }

    @Test
    void successfulProviderRefundFinalizesTheSameDurableOperation() {
        when(transactions.prepare(command)).thenReturn(prepared);
        when(transactions.finalizeRefund(41L, "provider-refund-request-41"))
                .thenReturn(completed);

        assertEquals(completed, coordinator.execute(command));

        assertEquals(1, gateway.processedRefunds);
        assertEquals(List.of("refund-request-41"), gateway.keys);
        verify(transactions).finalizeRefund(41L, "provider-refund-request-41");
    }

    @Test
    void providerFailureLeavesDatabaseFinalizationUntouched() {
        when(transactions.prepare(command)).thenReturn(prepared);
        gateway.failure = new IllegalStateException("provider down");

        assertThrows(IllegalStateException.class, () -> coordinator.execute(command));

        verify(transactions, never()).finalizeRefund(any(Long.class), any());
        assertEquals(0, gateway.processedRefunds);
    }

    @Test
    void providerSuccessThenDatabaseFailureRetriesWithTheSameProviderIdentity() {
        when(transactions.prepare(command)).thenReturn(prepared);
        when(transactions.finalizeRefund(eq(41L), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(completed);

        assertThrows(IllegalStateException.class, () -> coordinator.execute(command));
        assertEquals(completed, coordinator.execute(command));

        assertEquals(List.of("refund-request-41", "refund-request-41"), gateway.keys);
        assertEquals(1, gateway.processedRefunds,
                "the provider receives a retry but processes one logical refund");
        verify(transactions, times(2)).finalizeRefund(eq(41L), any());
    }

    @Test
    void completedOperationReplayDoesNotCallTheProviderAgain() {
        RefundCoordinator.PreparedRefund replay = new RefundCoordinator.PreparedRefund(
                prepared.refundRequestId(), prepared.payment(), prepared.displayAmount(),
                prepared.displayCurrency(), prepared.nativeAmount(), prepared.nativeCurrency(),
                prepared.reason(), prepared.providerOperationKey(), completed);
        when(transactions.prepareExisting(41L)).thenReturn(replay);

        assertEquals(completed, coordinator.resume(41L));

        assertEquals(0, gateway.calls);
        verify(transactions, never()).finalizeRefund(any(Long.class), any());
    }

    private static final class IdempotentGateway implements PaymentGateway {
        private final Map<String, GatewayRefund> completed = new HashMap<>();
        private final java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        private RuntimeException failure;
        private int calls;
        private int processedRefunds;

        @Override
        public boolean supports(PaymentMethod method) {
            return method == PaymentMethod.CARD;
        }

        @Override
        public String name() {
            return "idempotent-test";
        }

        @Override
        public GatewayCheckout createCheckout(Payment payment, String returnUrl,
                                              String providerOperationKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retireCheckout(String transactionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GatewayRefund refund(Payment payment, BigDecimal amount, String reason,
                                    String providerOperationKey) {
            calls++;
            keys.add(providerOperationKey);
            if (failure != null) {
                throw failure;
            }
            return completed.computeIfAbsent(providerOperationKey, key -> {
                processedRefunds++;
                return new GatewayRefund("re-" + key, amount, "provider-" + key);
            });
        }
    }
}
