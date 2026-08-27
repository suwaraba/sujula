package com.sujula.service.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deployment-specific payment settings: the bank details buyers transfer to,
 * and the shared secret that authenticates provider callbacks.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.payment")
public class PaymentProperties {

    /**
     * Shared secret a caller must present in the {@code X-Sujula-Signature}
     * header to post a payment callback. Blank disables the callback endpoint
     * outright — an unauthenticated endpoint that can mark orders paid is worse
     * than no endpoint at all.
     */
    private String callbackSecret = "";

    /** How long an unpaid gateway checkout stays open before it may be cancelled, in minutes. */
    private long checkoutTtlMinutes = 60;

    private final BankTransfer bankTransfer = new BankTransfer();

    /** Where a buyer sends a bank transfer, quoted back to them as payment instructions. */
    @Getter
    @Setter
    public static class BankTransfer {
        private String bankName = "";
        private String accountName = "";
        private String accountNumber = "";
        private String swift = "";
        private String branch = "";

        /** True once enough of the details are filled in to instruct a buyer. */
        public boolean isConfigured() {
            return !bankName.isBlank() && !accountName.isBlank() && !accountNumber.isBlank();
        }
    }
}
