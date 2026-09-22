package com.sujula.model.store;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.StorePermission;
import com.sujula.model.constant.StoreStaffStatus;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Somebody who works in a store without owning it.
 *
 * <p>Invited by email, because the person being invited frequently has no
 * account here yet — a seller adds a cousin or an assistant who has never used
 * the platform. So the invitation is keyed on the email address and the
 * {@code user} is filled in when they accept, rather than the row requiring a
 * user to exist before it can be written.
 *
 * <p>The invitation token is stored hashed, like every other bearer credential
 * in this system. A database dump full of live invitations is a way into other
 * people's shops.
 */
@Entity
@Table(name = "store_staff",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_store_staff_vendor_email", columnNames = {"vendor_id", "email"}),
       indexes = {
           @Index(name = "idx_staff_vendor",  columnList = "vendor_id"),
           @Index(name = "idx_staff_user",    columnList = "user_id"),
           @Index(name = "idx_staff_token",   columnList = "inviteTokenHash")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoreStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    /**
     * The account, once there is one. Null while the invitation is outstanding.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Who was invited. Lower-cased on the way in, and the natural key here. */
    @Column(nullable = false, length = 200)
    private String email;

    /** What the owner typed, so a pending invitation is not just an address. */
    @Column(length = 150)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StoreStaffStatus status = StoreStaffStatus.INVITED;

    /**
     * What they may do, inside this store only.
     *
     * <p>A {@code Set} of a store-scoped enum: there is no value here that
     * reaches another vendor's data or the platform's.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "store_staff_permissions",
                     joinColumns = @JoinColumn(name = "staff_id"))
    @Column(name = "permission", length = 40)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<StorePermission> permissions = new LinkedHashSet<>();

    @Column(length = 64)
    private String inviteTokenHash;

    private LocalDateTime inviteExpiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_user_id")
    private User invitedBy;

    private LocalDateTime acceptedAt;
    private LocalDateTime revokedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public boolean isLive() {
        return status == StoreStaffStatus.ACTIVE || status == StoreStaffStatus.INVITED;
    }

    public boolean can(StorePermission permission) {
        return status.isLive() && permissions != null && permissions.contains(permission);
    }
}
