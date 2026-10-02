package com.sujula.service.refund;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.order.Payment;
import com.sujula.model.user.User;
import com.sujula.service.payment.PaymentGateway;
import com.sujula.service.reference.CurrencyCatalogue;

/** Coordinates the non-transactional provider step between two durable transactions. */
@Service
public class RefundCoordinator {

    private final RefundTransactionService transactions;
    private final List<PaymentGateway> gateways;
    private final CurrencyCatalogue currencies;

    public RefundCoordinator(RefundTransactionService transactions,
                             List<PaymentGateway> gateways,
                             CurrencyCatalogue currencies) {
        this.transactions = transactions;
        this.gateways = gateways;
        this.currencies = currencies;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RefundResult execute(RefundCommand command) {
        return execute(transactions.prepare(command));
    }

    /** Recovery entry point for an already durable operation. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RefundResult resume(long refundRequestId) {
        return execute(transactions.prepareExisting(refundRequestId));
    }

    private RefundResult execute(PreparedRefund prepared) {
        if (prepared.completedResult() != null) {
            return prepared.completedResult();
        }

        String providerResponse = null;
        Payment payment = prepared.payment();
        if (payment.getMethod().requiresGateway()) {
            PaymentGateway gateway = gateways.stream()
                    .filter(candidate -> candidate.supports(payment.getMethod()))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException(
                            "No payment provider is available to return this "
                                    + payment.getMethod().getDisplayName() + " payment."));

            PaymentGateway.GatewayRefund refunded = gateway.refund(
                    payment, prepared.displayAmount(), prepared.reason(),
                    prepared.providerOperationKey());
            BigDecimal actual = currencies.round(refunded.amount(), prepared.displayCurrency());
            if (actual == null || actual.compareTo(prepared.displayAmount()) != 0) {
                throw new IllegalStateException("The provider reported refunding " + actual + " "
                        + prepared.displayCurrency() + " for operation " + prepared.refundRequestId()
                        + ", but the durable request is for " + prepared.displayAmount() + ".");
            }
            providerResponse = refunded.rawResponse();
        }

        return transactions.finalizeRefund(prepared.refundRequestId(), providerResponse);
    }

    public record RefundCommand(
            User staff,
            Long paymentId,
            Long vendorOrderId,
            BigDecimal displayAmount,
            BigDecimal nativeAmount,
            boolean fullRefund,
            String reason,
            String password,
            String totpCode) {}

    public record PreparedRefund(
            long refundRequestId,
            Payment payment,
            BigDecimal displayAmount,
            String displayCurrency,
            BigDecimal nativeAmount,
            String nativeCurrency,
            String reason,
            String providerOperationKey,
            RefundResult completedResult) {}

    public record RefundResult(
            Long refundRequestId,
            String reference,
            Long vendorOrderId,
            String storeName,
            BigDecimal displayAmount,
            String displayCurrency,
            BigDecimal nativeAmount,
            String nativeCurrency,
            BigDecimal fxRate,
            java.time.LocalDateTime fxRateAt,
            boolean stepUpRequired,
            boolean fullSliceRefund) {}
}
