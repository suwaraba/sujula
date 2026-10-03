package com.sujula.controller;

import com.sujula.dto.request.payment.ConfirmPaymentRequest;
import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.user.User;
import com.sujula.service.PaymentService;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.payment.PaymentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void inPersonCollectionUsesTheAuthenticatedPrincipalAsCollector() {
        User principal = new User();
        principal.setId(44L);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        ConfirmPaymentRequest body = ConfirmPaymentRequest.builder()
                .collectionReference("COUNTER-41")
                .build();
        PaymentResponse response = mock(PaymentResponse.class);
        when(payments.collectInPerson(41L, body, 44L)).thenReturn(response);

        assertSame(response, controller.collect(authentication, 41L, body).getBody());
        verify(payments).collectInPerson(41L, body, 44L);
    }
}
