package com.sujula.model.user;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.BankAccountType;
import com.sujula.service.security.EncryptedStringConverter;

import java.time.LocalDateTime;

@Entity
@Table(name = "vendor_bank_accounts")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BankAccountType accountType;

    @Column(nullable = false)
    private String accountHolderName;

    @Column(nullable = false)
    private String bankName;

    /**
     * The account number, encrypted in the column.
     *
     * <p>The converter is on the mapping rather than in a service, so there is
     * no write path that reaches this column in clear — not an admin tool, not a
     * migration, not a repository method somebody adds next year.
     *
     * <p>Never returned to a client and never logged. What a vendor sees of
     * their own payout destination is {@link #accountNumberLast4}, which is
     * enough to recognise it and useless to anyone else.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 500)
    private String accountNumber;

    /** The tail of the account number, in clear, for display. */
    @Column(length = 4)
    private String accountNumberLast4;

    private String routingNumber;    // ACH (US)

    /** SEPA. Encrypted for the same reason as the account number. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 500)
    private String iban;

    @Column(length = 4)
    private String ibanLast4;

    private String swiftCode;        // international wires

    /**
     * Mobile money line. Encrypted, and the one most likely to be the real
     * payout route: most sellers here are paid to a phone rather than a bank.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 500)
    private String mobileMoneyPhone;

    @Column(length = 4)
    private String mobileMoneyLast4;

    /** Which provider the mobile-money line belongs to — Wave, Orange Money, QMoney. */
    @Column(length = 60)
    private String mobileMoneyProvider;

    /**
     * Who last changed where this store's money goes, and when.
     *
     * <p>Recorded because this is the single most valuable thing an attacker
     * inside a vendor's account can change, and the first question afterwards is
     * always when it changed and from which session.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_changed_by_user_id")
    private com.sujula.model.user.User lastChangedBy;

    private LocalDateTime lastChangedAt;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @Column(nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
