package com.sujula.model.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sujula.model.constant.PaymentChannel;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The single money record for an order — one row per order, whatever the method.
 *
 * <p>Every payment method the platform accepts lands here: a gateway checkout
 * (card, PayPal), a bank transfer the buyer makes out of band, and cash taken in
 * person at a store, a pickup point or the door. What differs between them is
 * only who is allowed to move this row to {@link PaymentStatus#PAID} and what
 * evidence they record while doing it, which is why the collector and the
 * provider reference are both kept here rather than in method-specific tables.
 *
 * <p>The order carries a copy of {@link #status} as its own payment flag, so
 * order lists and lookups never have to join to this table to answer "is it
 * paid?".
 */
@Entity
@Table(name = "payments",
       indexes = {
           @Index(name = "idx_payment_reference", columnList = "reference", unique = true),
           @Index(name = "idx_payment_txid",      columnList = "transactionId"),
           @Index(name = "idx_payment_status",    columnList = "status"),
           @Index(name = "idx_payment_method",    columnList = "method")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Guards against two confirmations (e.g. a webhook and an admin) racing on the same row. */
    @Version
    private Long version;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    @JsonIgnore   // prevents circular JSON serialisation (Order → Payment → Order)
    private Order order;

    /** Public, human-quotable identifier — the reference a buyer puts on a bank transfer. */
    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentMethod method;

    /** Amount due, pinned from the order total when the payment was initiated. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** How much of {@link #amount} has been returned to the buyer. */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal amountRefunded = BigDecimal.ZERO;

    /** ISO-4217 code of {@link #amount} — always the order's own currency. */
    @Column(nullable = false, length = 3)
    private String currency;

    // ── Gateway leg (online methods only) ─────────────────────────────────────

    /** The provider's own reference for this payment (PaymentIntent id, capture id, …). */
    @Column(length = 200)
    private String transactionId;

    /** Hosted checkout URL the buyer is redirected to, when the provider uses one. */
    @Column(length = 500)
    private String checkoutUrl;

    /**
     * Client-side secret the provider's SDK needs to render an inline payment
     * form. Kept in its own column so {@link #gatewayResponse} can be
     * overwritten by callback payloads without destroying it — a buyer who
     * abandons and returns still needs it to retry.
     */
    @Column(length = 500)
    private String clientSecret;

    /** Raw provider payload, kept for audit. Never serialised to clients. */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String gatewayResponse;

    // ── Offline / in-person leg ───────────────────────────────────────────────

    /**
     * What the buyer must do to pay by this method — bank details for a
     * transfer, or where and to whom the cash is handed over. Generated at
     * initiation so the client has something to display without knowing the
     * rules of each method.
     */
    @Column(columnDefinition = "TEXT")
    private String instructions;

    /** The person who confirmed the money: an admin, a driver, an operator, or vendor staff. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by_user_id")
    private User confirmedBy;

    /** Evidence the collector recorded — bank slip number, receipt number, teller reference. */
    @Column(length = 200)
    private String collectionReference;

    @Column(length = 500)
    private String note;

    /** Why the last attempt failed, shown to the buyer before they retry. */
    @Column(length = 500)
    private String failureReason;

    // ── Timestamps ────────────────────────────────────────────────────────────

    private LocalDateTime paidAt;
    private LocalDateTime refundedAt;
    private LocalDateTime cancelledAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The channel of {@link #method}, i.e. how this payment is confirmed. */
    public PaymentChannel getChannel() {
        return method != null ? method.getChannel() : null;
    }

    /** Amount still refundable: what was received, less what has already gone back. */
    public BigDecimal getRefundableAmount() {
        BigDecimal refunded = amountRefunded != null ? amountRefunded : BigDecimal.ZERO;
        return amount.subtract(refunded).max(BigDecimal.ZERO);
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }
}
