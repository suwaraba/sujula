package com.sujula.service.payment;

import static com.sujula.service.payment.ProviderPaymentTransitionPolicy.Decision.APPLY;
import static com.sujula.service.payment.ProviderPaymentTransitionPolicy.Decision.NO_OP;
import static com.sujula.service.payment.ProviderPaymentTransitionPolicy.Decision.REJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.sujula.model.constant.PaymentStatus;

class ProviderPaymentTransitionPolicyTest {

    @Test
    void successOnlySettlesOpenStates() {
        assertEquals(APPLY, decide(PaymentStatus.PENDING, PaymentStatus.PAID));
        assertEquals(APPLY, decide(PaymentStatus.AUTHORIZED, PaymentStatus.PAID));
        assertEquals(APPLY, decide(PaymentStatus.FAILED, PaymentStatus.PAID));
        assertEquals(NO_OP, decide(PaymentStatus.PAID, PaymentStatus.PAID));
        assertEquals(REJECT, decide(PaymentStatus.CANCELLED, PaymentStatus.PAID));
        assertEquals(REJECT, decide(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.PAID));
        assertEquals(REJECT, decide(PaymentStatus.REFUNDED, PaymentStatus.PAID));
    }

    @Test
    void failureOnlyClosesPendingOrAuthorizedPayments() {
        assertEquals(APPLY, decide(PaymentStatus.PENDING, PaymentStatus.FAILED));
        assertEquals(APPLY, decide(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED));
        assertEquals(NO_OP, decide(PaymentStatus.FAILED, PaymentStatus.FAILED));
        assertEquals(REJECT, decide(PaymentStatus.PAID, PaymentStatus.FAILED));
        assertEquals(REJECT, decide(PaymentStatus.CANCELLED, PaymentStatus.FAILED));
        assertEquals(REJECT, decide(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.FAILED));
        assertEquals(REJECT, decide(PaymentStatus.REFUNDED, PaymentStatus.FAILED));
    }

    @Test
    void refundTargetsRequirePreviouslySettledMoney() {
        assertEquals(REJECT, decide(PaymentStatus.PENDING, PaymentStatus.REFUNDED));
        assertEquals(REJECT, decide(PaymentStatus.AUTHORIZED, PaymentStatus.PARTIALLY_REFUNDED));
        assertEquals(REJECT, decide(PaymentStatus.FAILED, PaymentStatus.REFUNDED));
        assertEquals(APPLY, decide(PaymentStatus.PAID, PaymentStatus.PARTIALLY_REFUNDED));
        assertEquals(APPLY, decide(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));
    }

    private static ProviderPaymentTransitionPolicy.Decision decide(
            PaymentStatus current, PaymentStatus target) {
        return ProviderPaymentTransitionPolicy.decide(current, target);
    }
}
