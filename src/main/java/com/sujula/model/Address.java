package com.sujula.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.user.User;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "addresses")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String label;               // "Home", "Work", "Other"

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String street;

    private String apartmentSuite;

    @Column(nullable = false)
    private String city;

    private String state;              

    private String postalCode;

    @Column(nullable = false, length = 2)
    private String countryCode;

    private Double latitude;
    private Double longitude;

    /**
     * How much the pin above is worth.
     *
     * <p>Stored rather than recomputed because it is a fact about one geocoding
     * call at one moment: the provider's coverage of Brikama improves, a street
     * gets numbered, and the same query returns something better next year. What
     * was known when this address was saved is what the client was told, and
     * re-deriving it would quietly change the answer under a buyer who already
     * confirmed their pin.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private GeocodeConfidence geocodeConfidence = GeocodeConfidence.NONE;

    /** When the coordinates were last resolved. Null when they never were. */
    private LocalDateTime geocodedAt;

    /**
     * When the owner confirmed the pin on a map.
     *
     * <p>Set, it makes the address immune to re-geocoding: an edit to the
     * apartment number must not move a pin the resident placed themselves.
     */
    private LocalDateTime pinConfirmedAt;

    /**
     * When this address was removed, for the ones that cannot simply go.
     *
     * <p>An address an order was placed against is kept, deleted-but-present, so
     * that order still resolves to the row it named. One nobody has ordered
     * against is deleted outright — there is no reason to accumulate rows
     * carrying a name, a phone number and a location for their own sake, and
     * least of all for someone who asked for them to be gone.
     */
    private LocalDateTime deletedAt;

    /** Whether this address is still in the owner's book. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    @Column(nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
