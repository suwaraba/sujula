package com.sujula.controller;

import com.sujula.exceptions.BadRequestException;
import com.sujula.service.checkout.CheckoutService;
import com.sujula.service.idempotency.IdempotencyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Checkout must refuse an absent key before resolving or executing business work. */
class CheckoutControllerIdempotencyTest {

    private final CheckoutService checkout = mock(CheckoutService.class);
    private final AuthenticatedCaller caller = mock(AuthenticatedCaller.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final CheckoutController controller = new CheckoutController(checkout, caller, idempotency);

    @Test
    void missingKeyIsRejectedBeforeBusinessExecution() {
        assertThrows(BadRequestException.class, () -> controller.checkout(null, null, null));

        verifyNoInteractions(checkout, caller, idempotency);
    }

    @Test
    void blankKeyIsRejectedBeforeBusinessExecution() {
        assertThrows(BadRequestException.class, () -> controller.checkout(null, "   ", null));

        verifyNoInteractions(checkout, caller, idempotency);
    }

    @Test
    void missingPaymentRetryKeyIsRejectedBeforeBusinessExecution() {
        assertThrows(BadRequestException.class,
                () -> controller.retryPayment(null, 41L, null, null));

        verifyNoInteractions(checkout, caller, idempotency);
    }

    @Test
    void blankPaymentRetryKeyIsRejectedBeforeBusinessExecution() {
        assertThrows(BadRequestException.class,
                () -> controller.retryPayment(null, 41L, "   ", null));

        verifyNoInteractions(checkout, caller, idempotency);
    }
}
