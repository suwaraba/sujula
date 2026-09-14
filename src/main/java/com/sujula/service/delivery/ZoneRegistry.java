package com.sujula.service.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import com.sujula.model.logistics.DeliveryZone;
import com.sujula.repository.logistics.DeliveryZoneRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Which zone a place is in — held in memory, and the only writer of a zone's
 * bounding box.
 *
 * <p>This is the serviceability cache the admin zone endpoints invalidate. It
 * exists because the question it answers cannot be pushed into a query: MySQL
 * spatial indexes are not switched on in every environment this runs in, so
 * containment is a walk over polygons, and walking them out of the database on
 * every product page is not a thing. Zones change a few times a year and are
 * counted in dozens, so holding the parsed shapes is both cheap and the only
 * sensible shape for the problem.
 *
 * <p>Invalidation is explicit rather than timed. A zone edited in the back
 * office must take effect on the next request, not in five minutes — the reason
 * somebody edits one is usually that parcels are being quoted wrong right now.
 *
 * <p><b>C1.</b> Everything here takes a delivery position. There is no overload
 * that takes a buyer, a payer, or a session, because the answer must not depend
 * on where the person paying happens to be sitting: the sister in Serrekunda is
 * inside the Serrekunda zone whether the phone was paid for from Madrid or from
 * the next street.
 */
@Slf4j
@Component
public class ZoneRegistry {

    private final DeliveryZoneRepository zones;
    private final ObjectMapper mapper;

    /** Bumped on every write. Reported to administrators so an edit is visibly live. */
    private final AtomicLong version = new AtomicLong(0);

    /** Null means "not loaded"; replaced wholesale, never mutated in place. */
    private volatile List<Live> snapshot;

    public ZoneRegistry(DeliveryZoneRepository zones, ObjectMapper mapper) {
        this.zones = zones;
        this.mapper = mapper;
    }

    /** A zone with its polygon already parsed, as the cache holds it. */
    private record Live(Long id, String code, String name, String countryCode, int priority,
                        boolean serviceable, String unserviceableReason, GeoJsonPolygon shape) {}

    /**
     * Parses and measures a shape, and writes the measurements onto the zone.
     *
     * <p>The single point at which a polygon becomes a bounding box. Doing it
     * anywhere else is how a box comes to disagree with its own shape, and a box
     * that disagrees silently excludes addresses that are inside the polygon.
     *
     * @return the parsed shape, so a caller can report on it without parsing twice
     */
    public GeoJsonPolygon applyGeometry(DeliveryZone zone, String geoJson) {
        GeoJsonPolygon shape = GeoJsonPolygon.parse(geoJson, mapper);
        zone.setGeometry(geoJson);
        zone.applyBounds(shape.minLatitude(), shape.maxLatitude(),
                shape.minLongitude(), shape.maxLongitude(), shape.vertexCount());
        return shape;
    }

    /** Reads a shape without writing anything, using this registry's own mapper. */
    public GeoJsonPolygon parse(String geoJson) {
        return GeoJsonPolygon.parse(geoJson, mapper);
    }

    /** Drops the held shapes. The next question reloads them. */
    public long invalidate() {
        this.snapshot = null;
        long now = version.incrementAndGet();
        log.info("[Zones] Cache invalidated, now at version {}", now);
        return now;
    }

    public long version() {
        return version.get();
    }

    /** How many zones the cache is currently holding, loading if it must. */
    public int size() {
        return load().size();
    }

    /**
     * The zone a delivery position falls in, or empty when none does.
     *
     * <p>Highest priority wins, because overlap is how a denser city rate is
     * expressed inside a national zone rather than a mistake to be prevented.
     *
     * @param latitude  where the goods are going
     * @param longitude where the goods are going
     */
    public Optional<DeliveryZone> zoneAtDestination(Double latitude, Double longitude) {
        return liveAtDestination(latitude, longitude)
                .flatMap(live -> zones.findById(live.id()));
    }

    /** The same lookup without touching the database — id, name and standing only. */
    public Optional<ZoneMatch> matchAtDestination(Double latitude, Double longitude) {
        return liveAtDestination(latitude, longitude)
                .map(live -> new ZoneMatch(live.id(), live.code(), live.name(), live.countryCode(),
                        live.serviceable(), live.unserviceableReason()));
    }

    /**
     * Whether the platform delivers to this position.
     *
     * <p>A destination inside no zone at all is <em>not</em> refused. Zones are
     * how a platform says "not here" about somewhere it has looked at, and on the
     * day this feature ships no zone has been drawn anywhere — refusing what has
     * not been drawn yet would close the marketplace. Only an explicit
     * unserviceable zone is a refusal.
     */
    public Serviceability serviceabilityAtDestination(Double latitude, Double longitude) {
        Optional<ZoneMatch> match = matchAtDestination(latitude, longitude);
        if (match.isEmpty()) {
            return new Serviceability(true, null, null);
        }
        ZoneMatch zone = match.get();
        return new Serviceability(zone.serviceable(), zone,
                zone.serviceable() ? null
                        : (zone.unserviceableReason() == null || zone.unserviceableReason().isBlank()
                                ? "The platform is not delivering to " + zone.name() + " at the moment."
                                : zone.unserviceableReason()));
    }

    public record ZoneMatch(Long id, String code, String name, String countryCode,
                            boolean serviceable, String unserviceableReason) {}

    public record Serviceability(boolean deliverable, ZoneMatch zone, String reason) {}

    private Optional<Live> liveAtDestination(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return Optional.empty();
        }
        for (Live live : load()) {
            if (live.shape().contains(latitude, longitude)) {
                return Optional.of(live);
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    protected List<Live> load() {
        List<Live> held = snapshot;
        if (held != null) {
            return held;
        }
        synchronized (this) {
            if (snapshot != null) {
                return snapshot;
            }
            List<Live> built = new ArrayList<>();
            for (DeliveryZone zone : zones.findLive()) {
                try {
                    built.add(new Live(zone.getId(), zone.getCode(), zone.getName(),
                            zone.getCountryCode(), zone.getPriority(), zone.isServiceable(),
                            zone.getUnserviceableReason(),
                            GeoJsonPolygon.parse(zone.getGeometry(), mapper)));
                } catch (RuntimeException e) {
                    // One unreadable shape must not take the whole map down with
                    // it: the rest of the country still has to be quotable. It is
                    // logged loudly because a zone that cannot be parsed is a
                    // zone silently delivering nothing.
                    log.error("[Zones] Zone {} ({}) has a shape that will not parse and is being "
                            + "ignored: {}", zone.getId(), zone.getCode(), e.getMessage());
                }
            }
            snapshot = List.copyOf(built);
            log.info("[Zones] Loaded {} zone(s) at version {}", built.size(), version.get());
            return snapshot;
        }
    }
}
