package com.sujula.model.platform;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A switch somebody can throw without a deploy.
 *
 * <p>Every one of these carries who last threw it and why. A flag with no
 * author is a flag nobody will dare turn back on: six months later the only
 * thing anybody knows is that it is off, and turning it on becomes an act of
 * faith rather than a decision.
 *
 * <p>Deliberately not a rollout percentage or a per-user targeting rule. This
 * platform has one deployment and a few thousand users; a flag that is on or
 * off for everybody is a thing an operator can reason about at two in the
 * morning, and a bucketing function is not.
 */
@Entity
@Table(name = "feature_flags",
       indexes = @Index(name = "idx_flag_key", columnList = "flagKey", unique = true))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code delivery.safe-drop}, {@code checkout.guest}. Dotted, lower case. */
    @Column(nullable = false, unique = true, length = 80)
    private String flagKey;

    @Column(nullable = false, length = 200)
    private String label;

    /** What turning it off actually does, in words an operator can act on. */
    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /**
     * Whether a client may be told about this flag.
     *
     * <p>Most are not. Telling a browser that {@code payouts.second-approver} is
     * on tells anybody looking how the platform is defended, and a flag list is
     * the sort of thing that ends up in a public config endpoint by accident.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean clientVisible = false;

    private Long lastChangedByUserId;

    private LocalDateTime lastChangedAt;

    /** Why it was last moved. Shown beside the flag, not buried in the audit log. */
    @Column(length = 500)
    private String lastChangeReason;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
