package com.sujula.service.payment;

import com.sujula.model.constant.PaymentStatus;

/**
 * The provider-facing payment state machine.
 *
 * <p>Callbacks are facts arriving out of order, not authority to reopen a
 * terminal financial state. Both webhook processing and the legacy callback
 * surface use this policy so their answers cannot drift apart.
 */
public final class ProviderPaymentTransitionPolicy {

    public enum Decision { APPLY, NO_OP, REJECT }

    private ProviderPaymentTransitionPolicy() {
    }

    public static Decision decide(PaymentStatus current, PaymentStatus target) {
        if (current == null || target == null) {
            return Decision.REJECT;
        }
        if (current == target) {
            return Decision.NO_OP;
        }
        return switch (target) {
            case PAID -> switch (current) {
                case PENDING, AUTHORIZED, FAILED -> Decision.APPLY;
                default -> Decision.REJECT;
            };
            case FAILED -> switch (current) {
                case PENDING, AUTHORIZED -> Decision.APPLY;
                default -> Decision.REJECT;
            };
            case AUTHORIZED -> current == PaymentStatus.PENDING
                    ? Decision.APPLY : Decision.REJECT;
            case CANCELLED -> switch (current) {
                case PENDING, AUTHORIZED, FAILED -> Decision.APPLY;
                default -> Decision.REJECT;
            };
            case PARTIALLY_REFUNDED, REFUNDED -> switch (current) {
                case PAID, PARTIALLY_REFUNDED -> Decision.APPLY;
                default -> Decision.REJECT;
            };
            case PENDING -> Decision.REJECT;
        };
    }
}
