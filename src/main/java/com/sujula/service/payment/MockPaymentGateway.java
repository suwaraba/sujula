package com.sujula.service.payment;

import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.order.Payment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * Stands in for a real card/PayPal provider until one is integrated.
 *
 * <p>Card and PayPal are the two methods that need a provider, and with no
 * {@link PaymentGateway} bean registered for them checkout reports them as
 * unavailable — which leaves no way to exercise the paid-order path at all.
 * This bean fills that gap: it accepts every charge, reports the payment as
 * settled the moment checkout is opened, and refunds whatever it is asked to.
 *
 * <p>It is <strong>off unless explicitly switched on</strong>
 * ({@code sujula.payment.mock.enabled=true}) and it refuses to start under a
 * {@code prod} profile. A bean that marks orders paid without money moving is a
 * fraud engine if it ever reaches production, so it announces itself loudly in
 * the log every time it runs and is never the default.
 *
 * <p>Deleting this class is the intended end state: when a real gateway is
 * implemented against the same interface, drop the property and this bean
 * disappears with it. Nothing else in the payment code knows it exists.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sujula.payment.mock", name = "enabled", havingValue = "true")
public class MockPaymentGateway implements PaymentGateway {

    private final Environment environment;

    public MockPaymentGateway(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void refuseToRunInProduction() {
        for (String profile : environment.getActiveProfiles()) {
            if (profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production")) {
                throw new IllegalStateException(
                        "sujula.payment.mock.enabled is true under the '" + profile + "' profile. "
                                + "The mock gateway marks orders paid without taking any money. "
                                + "Remove the property or integrate a real gateway.");
            }
        }
        log.warn("""

                ================================================================
                 MOCK PAYMENT GATEWAY IS ACTIVE
                 Card and PayPal orders will be marked PAID without any money
                 moving. This is a development stand-in — unset
                 sujula.payment.mock.enabled before this reaches real buyers.
                ================================================================""");
    }

    @Override
    public boolean supports(PaymentMethod method) {
        // Exactly the methods a provider would handle. Bank transfer and the
        // in-person methods are confirmed by a person and never touch a gateway.
        return method != null && method.requiresGateway();
    }

    @Override
    public String name() {
        return "mock";
    }

    /**
     * "Charges" the card. There is no hosted page to send the buyer to, so the
     * checkout URL is null and {@link #settlesImmediately()} tells the service
     * to settle rather than wait for a callback that will never arrive.
     */
    @Override
    public GatewayCheckout createCheckout(Payment payment, String returnUrl) {
        String transactionId = "MOCK-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);

        log.warn("[Payment] MOCK gateway accepting {} {} for {} without charging anything (tx {})",
                payment.getAmount(), payment.getCurrency(), payment.getReference(), transactionId);

        return new GatewayCheckout(transactionId, null, null,
                "{\"gateway\":\"mock\",\"status\":\"succeeded\",\"transactionId\":\"" + transactionId + "\"}");
    }

    @Override
    public boolean settlesImmediately() {
        return true;
    }

    @Override
    public GatewayRefund refund(Payment payment, BigDecimal amount, String reason) {
        String refundId = "MOCKREF-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase(Locale.ROOT);

        log.warn("[Payment] MOCK gateway returning {} {} for {} without moving anything (refund {})",
                amount, payment.getCurrency(), payment.getReference(), refundId);

        return new GatewayRefund(refundId, amount,
                "{\"gateway\":\"mock\",\"status\":\"refunded\",\"refundId\":\"" + refundId + "\"}");
    }
}
