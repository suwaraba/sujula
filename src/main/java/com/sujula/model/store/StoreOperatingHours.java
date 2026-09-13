package com.sujula.model.store;

import java.time.DayOfWeek;
import java.time.LocalTime;

import com.sujula.model.user.Vendor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * When a driver can actually collect from this store, one row per weekday.
 *
 * <p>Structured rather than the free-text opening hours a storefront usually
 * carries, because this is read by dispatch and not only by shoppers. A pickup
 * scheduled for 03:00 because "Mon-Sat 9-6" was a string nobody could parse is a
 * driver standing outside a shuttered shop.
 *
 * <p>Times are local to the store. A marketplace whose sellers are in Banjul and
 * Dakar and whose buyers are in Madrid has no single clock, and the only one
 * that matters for a collection is the one on the wall where the goods are.
 */
@Entity
@Table(name = "store_operating_hours",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_store_hours_vendor_day", columnNames = {"vendor_id", "dayOfWeek"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoreOperatingHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    /**
     * Shut all day.
     *
     * <p>A separate flag rather than two null times, so "closed on Sunday" and
     * "nobody has filled Sunday in yet" are different answers. Dispatch needs to
     * tell them apart: the first is a fact, the second is a store that should be
     * asked.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean closed = false;

    private LocalTime opensAt;
    private LocalTime closesAt;
}
