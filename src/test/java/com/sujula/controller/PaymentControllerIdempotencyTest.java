package com.sujula.controller;

import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.service.PaymentService;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.payment.PaymentProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentControllerIdempotencyTest {

    private final PaymentService payments = mock(PaymentService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final PaymentController controller =
            new PaymentController(payments, new PaymentProperties(), idempotency);
    private final InitiatePaymentRequest request = InitiatePaymentRequest.builder()
            .method(PaymentMethod.CARD)
            .build();

    @Test
    void authenticatedPaymentRejectsMissingKeyBeforeAuthenticationOrBusinessWork() {
        assertThrows(BadRequestException.class,
                () -> controller.pay(null, 41L, null, request));

        verifyNoInteractions(payments, idempotency);
    }

    @Test
    void authenticatedPaymentRejectsBlankKeyBeforeAuthenticationOrBusinessWork() {
        assertThrows(BadRequestException.class,
                () -> controller.pay(null, 41L, "   ", request));

        verifyNoInteractions(payments, idempotency);
    }

    @Test
    void guestPaymentRejectsMissingKeyBeforeBusinessWork() {
        assertThrows(BadRequestException.class,
                () -> controller.guestPay("SJL-41", "guest@example.com", null, request));

        verifyNoInteractions(payments, idempotency);
    }

    @Test
    void guestPaymentRejectsBlankKeyBeforeBusinessWork() {
        assertThrows(BadRequestException.class,
                () -> controller.guestPay("SJL-41", "guest@example.com", "   ", request));

        verifyNoInteractions(payments, idempotency);
    }
}
