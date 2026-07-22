package com.sujula.model.user;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.BankAccountType;

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

    // Stored encrypted at rest — do not log
    @Column(nullable = false)
    private String accountNumber;

    private String routingNumber;    // ACH (US)
    private String iban;             // SEPA (EU)
    private String swiftCode;        // international wires
    private String mobileMoneyPhone; // mobile money provider number

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
