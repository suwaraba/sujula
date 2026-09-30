package com.sujula.service.payment;

import com.sujula.model.constant.PaymentMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PaymentOperationTest {

    @Test
    void sameLogicalOperationDerivesTheSameProviderIdentityAndReference() {
        PaymentOperation first = PaymentOperation.of(
                "user:41:payment.initiate:7", "retry-key-1");
        PaymentOperation retried = PaymentOperation.of(
                "user:41:payment.initiate:7", " retry-key-1 ");

        assertEquals(first.providerKey(), retried.providerKey());
        assertEquals(first.paymentReference(), retried.paymentReference());
        assertEquals(first.fingerprint(
                        "order:7", PaymentMethod.CARD, "https://shop/return", " buyer note "),
                retried.fingerprint(
                        "order:7", PaymentMethod.CARD, " https://shop/return ", "buyer note"));
    }

    @Test
    void aDifferentOperationDerivesADifferentProviderIdentityAndReference() {
        PaymentOperation first = PaymentOperation.of(
                "user:41:payment.initiate:7", "retry-key-1");
        PaymentOperation next = PaymentOperation.of(
                "user:41:payment.initiate:7", "retry-key-2");

        assertNotEquals(first.providerKey(), next.providerKey());
        assertNotEquals(first.paymentReference(), next.paymentReference());
    }

    @Test
    void changingThePaymentMethodChangesTheLocalRequestFingerprint() {
        PaymentOperation operation = PaymentOperation.of(
                "user:41:payment.initiate:7", "retry-key-1");

        assertNotEquals(
                operation.fingerprint("order:7", PaymentMethod.CARD, null, null),
                operation.fingerprint("order:7", PaymentMethod.BANK_TRANSFER, null, null));
    }

    @Test
    void changingThePaymentNoteChangesTheLocalRequestFingerprint() {
        PaymentOperation operation = PaymentOperation.of(
                "user:41:payment.initiate:7", "retry-key-1");

        assertNotEquals(
                operation.fingerprint("order:7", PaymentMethod.CARD, null, "first note"),
                operation.fingerprint("order:7", PaymentMethod.CARD, null, "second note"));
    }

    @Test
    void sameRawCheckoutKeyForDifferentQuotesCannotReuseAPaymentReference() {
        PaymentOperation firstQuote = PaymentOperation.of(
                "user:41:checkout.place:payment:quote:q-1", "checkout-key");
        PaymentOperation secondQuote = PaymentOperation.of(
                "user:41:checkout.place:payment:quote:q-2", "checkout-key");

        assertNotEquals(firstQuote.providerKey(), secondQuote.providerKey());
        assertNotEquals(firstQuote.paymentReference(), secondQuote.paymentReference());
    }
}
