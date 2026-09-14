package com.sujula.service.delivery;

import java.util.Map;
import java.util.Optional;

/**
 * Roughly where a country is, for catching a zone drawn in the wrong place.
 *
 * <p>Not a border. These are generous boxes around the countries this platform
 * delivers to, and they exist for one purpose: a polygon uploaded with its
 * coordinates the wrong way round parses cleanly, stores cleanly, and then
 * contains nothing at all. In most of the world the swap produces a latitude
 * beyond ±90 and {@link GeoJsonPolygon} refuses it outright — but The Gambia
 * sits at 13°N 16°W, and swapped that is 16°N 13°E, which is a perfectly legal
 * position in the middle of Niger. Nobody would notice for a month.
 *
 * <p>Deliberately generous rather than accurate. The question is "is this shape
 * in roughly the country it claims", not "is this shape inside the border" —
 * a zone whose edge runs a few kilometres over a frontier is a normal zone, and
 * refusing it would be refusing the truth about where people live.
 *
 * <p>A country not listed here is not checked. Adding a country to the platform
 * should not require a coordinate box first; the check is a safety net, and a
 * safety net that blocks expansion is worse than none.
 */
public final class CountryBounds {

    private CountryBounds() {}

    /** minLat, maxLat, minLng, maxLng — padded by roughly half a degree. */
    public record Box(double minLat, double maxLat, double minLng, double maxLng) {
        public boolean contains(double lat, double lng) {
            return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
        }
        /** Whether a shape's own box sits anywhere within this country's. */
        public boolean overlaps(double otherMinLat, double otherMaxLat,
                                double otherMinLng, double otherMaxLng) {
            return otherMinLat <= maxLat && otherMaxLat >= minLat
                    && otherMinLng <= maxLng && otherMaxLng >= minLng;
        }
    }

    private static final Map<String, Box> BOXES = Map.ofEntries(
            // West Africa — the countries goods are actually delivered in.
            Map.entry("GM", new Box(12.8,  14.0, -17.4, -13.3)),   // The Gambia
            Map.entry("SN", new Box(12.0,  17.0, -18.0, -11.0)),   // Senegal
            Map.entry("GW", new Box(10.6,  12.9, -16.9, -13.5)),   // Guinea-Bissau
            Map.entry("GN", new Box( 7.0,  13.0, -15.5,  -7.5)),   // Guinea
            Map.entry("ML", new Box( 9.9,  25.5, -12.5,   4.5)),   // Mali
            Map.entry("MR", new Box(14.5,  27.5, -17.5,  -4.5)),   // Mauritania
            Map.entry("SL", new Box( 6.5,  10.2, -13.5, -10.1)),   // Sierra Leone
            Map.entry("NG", new Box( 3.8,  14.2,   2.2,  15.0)),   // Nigeria

            // Places goods are bought from rather than delivered to, listed so a
            // zone drawn for a European courier leg is checked as well.
            Map.entry("GB", new Box(49.5,  61.2, -8.8,    2.2)),
            Map.entry("ES", new Box(27.4,  44.0, -18.5,    4.8)),
            Map.entry("FR", new Box(41.0,  51.5,  -5.5,    9.9)),
            Map.entry("IT", new Box(35.3,  47.3,   6.5,   18.7)),
            Map.entry("DE", new Box(47.2,  55.2,   5.7,   15.2)),
            Map.entry("SE", new Box(55.2,  69.2,  10.8,   24.3)),
            Map.entry("US", new Box(18.8,  71.5,-179.2,  -66.8)),
            Map.entry("CA", new Box(41.6,  83.2,-141.1,  -52.5)));

    /** The box for a country, or empty when this platform has none for it. */
    public static Optional<Box> of(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BOXES.get(countryCode.trim().toUpperCase()));
    }
}
