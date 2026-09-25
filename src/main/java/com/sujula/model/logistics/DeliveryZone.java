package com.sujula.model.logistics;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An area the platform has decided something about — a polygon on a map.
 *
 * <p>A zone is a <em>delivery</em> concept and nothing else. It answers "can a
 * parcel get here, and at what rate", and it is matched against the destination
 * the goods are going to. It is never matched against where the payer happens to
 * be: a buyer in Madrid sending a phone to Serrekunda is inside the Serrekunda
 * zone for every purpose this class serves, and consulting their own position
 * would put them outside every zone the platform operates and refuse the order.
 *
 * <p>The polygon is stored as the GeoJSON that was uploaded, verbatim, rather
 * than as a parsed structure. It is what the administrator can be shown back and
 * what they can re-upload to another system; a shape re-serialised from our own
 * parse is a shape that has quietly changed. The bounding box beside it is
 * derived from that same text on every write, and exists so a containment test
 * can reject the overwhelming majority of points with four comparisons instead
 * of walking a ring of several hundred vertices.
 */
@Entity
@Table(name = "delivery_zones",
       indexes = {
           @Index(name = "idx_zone_country", columnList = "countryCode"),
           @Index(name = "idx_zone_active",  columnList = "active"),
           @Index(name = "idx_zone_box",     columnList = "minLatitude, maxLatitude")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short stable handle an operator can type: {@code GM-KMC}, {@code SN-DKR-PLATEAU}. */
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    /** ISO 3166-1 alpha-2 of the country this zone lies in. */
    @Column(nullable = false, length = 2)
    private String countryCode;

    /**
     * The uploaded GeoJSON geometry, exactly as it arrived.
     *
     * <p>A {@code Polygon} or a {@code MultiPolygon}. Kept as text because this
     * deployment runs on MySQL without the spatial extensions switched on in
     * every environment, and because a polygon nobody can hand back unchanged is
     * a polygon that cannot be audited.
     */
    // LONGTEXT on MySQL, text on PostgreSQL: the type, not a vendor keyword.
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String geometry;

    // ── Derived bounding box ─────────────────────────────────────────────────
    //
    // Recomputed from the geometry on every write by ZoneRegistry, never set by
    // hand. A box that disagrees with its polygon is worse than no box: it
    // silently excludes points that are inside the shape.

    @Setter(AccessLevel.NONE) private Double minLatitude;
    @Setter(AccessLevel.NONE) private Double maxLatitude;
    @Setter(AccessLevel.NONE) private Double minLongitude;
    @Setter(AccessLevel.NONE) private Double maxLongitude;

    /** How many vertices the outer rings carry, for the administrator's own sanity. */
    @Setter(AccessLevel.NONE)
    @Column(nullable = false)
    @Builder.Default
    private int vertexCount = 0;

    /**
     * Whether the platform delivers here at all.
     *
     * <p>Separate from {@link #active}: a zone can exist, be drawn, and be used
     * for pricing while the platform has decided not to serve it — a river
     * crossing with no ferry this month. Deleting the zone instead would lose
     * the shape and the reason.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean serviceable = true;

    @Column(length = 400)
    private String unserviceableReason;

    /**
     * Which zone wins when polygons overlap; higher first.
     *
     * <p>Overlap is normal rather than a mistake — a city zone drawn inside a
     * national one is how a denser rate is expressed — so the resolution has to
     * be stated rather than left to whichever row the database returned first.
     */
    @Column(nullable = false)
    @Builder.Default
    private int priority = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Who last drew it, for the audit trail the shape itself cannot carry. */
    private Long lastEditedByUserId;

    /**
     * Stores a freshly measured box. Called by {@code ZoneRegistry} after parsing.
     *
     * <p>One setter for all five numbers rather than five, because four
     * coordinates and a count that were measured together must be written
     * together — a half-updated box is exactly the silent-exclusion bug above.
     */
    public void applyBounds(double minLat, double maxLat, double minLng, double maxLng,
                            int vertices) {
        this.minLatitude = minLat;
        this.maxLatitude = maxLat;
        this.minLongitude = minLng;
        this.maxLongitude = maxLng;
        this.vertexCount = vertices;
    }

    /** A cheap rejection: outside the box is certainly outside the polygon. */
    public boolean boxContains(double lat, double lng) {
        return minLatitude != null && maxLatitude != null
                && minLongitude != null && maxLongitude != null
                && lat >= minLatitude && lat <= maxLatitude
                && lng >= minLongitude && lng <= maxLongitude;
    }

    /** Whether this zone should be consulted when routing a parcel today. */
    public boolean isLive() {
        return active;
    }
}
