package com.sujula.service.delivery;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sujula.exceptions.BadRequestException;

/**
 * A GeoJSON {@code Polygon} or {@code MultiPolygon}, parsed and answerable.
 *
 * <p>Written here rather than pulled in with a geometry library because the two
 * questions this platform asks of a shape — "where is its bounding box" and "is
 * this point inside it" — are a hundred lines, and a coordinate system this code
 * gets to state rather than inherit. Everything here is WGS 84 degrees, the
 * order is GeoJSON's own {@code [longitude, latitude]}, and the single most
 * common way to get zones wrong is to read that pair the other way round. The
 * parser rejects a pair whose latitude is out of range for exactly that reason:
 * a polygon around Banjul with its coordinates swapped is a polygon in the
 * Atlantic, and it would otherwise fail silently by simply containing nothing.
 *
 * <p>Containment is ray casting on the plane. Over a zone the size of a city or
 * a region the error against a proper geodesic test is far below the accuracy of
 * the addresses being tested, and a zone drawn across a pole or the antimeridian
 * is not a thing this marketplace has.
 */
public final class GeoJsonPolygon {

    /** Outer ring first, then any holes, per polygon. */
    private final List<List<double[]>> rings;
    private final int polygonCount;

    private final double minLat;
    private final double maxLat;
    private final double minLng;
    private final double maxLng;
    private final int vertexCount;

    private GeoJsonPolygon(List<List<double[]>> rings, int polygonCount,
                           double minLat, double maxLat, double minLng, double maxLng,
                           int vertexCount) {
        this.rings = rings;
        this.polygonCount = polygonCount;
        this.minLat = minLat;
        this.maxLat = maxLat;
        this.minLng = minLng;
        this.maxLng = maxLng;
        this.vertexCount = vertexCount;
    }

    public double minLatitude()  { return minLat; }
    public double maxLatitude()  { return maxLat; }
    public double minLongitude() { return minLng; }
    public double maxLongitude() { return maxLng; }
    public int vertexCount()     { return vertexCount; }
    public int polygonCount()    { return polygonCount; }

    /**
     * Reads a geometry, or says precisely what is wrong with it.
     *
     * <p>Accepts a bare geometry, a {@code Feature} wrapping one, or a
     * {@code FeatureCollection} whose features are all polygons — because those
     * are the three things that come out of the drawing tools an administrator
     * will actually use, and telling somebody their export is "invalid GeoJSON"
     * when it is what QGIS produced is not a useful error.
     */
    public static GeoJsonPolygon parse(String geoJson, ObjectMapper mapper) {
        if (geoJson == null || geoJson.isBlank()) {
            throw new BadRequestException("A zone needs a shape. Upload the GeoJSON polygon.");
        }
        JsonNode root;
        try {
            root = mapper.readTree(geoJson);
        } catch (Exception e) {
            throw new BadRequestException("That is not valid JSON: " + e.getMessage());
        }

        List<JsonNode> geometries = new ArrayList<>();
        collectGeometries(root, geometries);
        if (geometries.isEmpty()) {
            throw new BadRequestException(
                    "No polygon in that file. A zone needs a Polygon or a MultiPolygon — a point "
                            + "or a line has no inside for a parcel to be delivered to.");
        }

        List<List<double[]>> rings = new ArrayList<>();
        int polygons = 0;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        int vertices = 0;

        for (JsonNode geometry : geometries) {
            String type = geometry.path("type").asText("");
            JsonNode coordinates = geometry.path("coordinates");
            List<JsonNode> polygonNodes = new ArrayList<>();
            if ("Polygon".equals(type)) {
                polygonNodes.add(coordinates);
            } else {
                coordinates.forEach(polygonNodes::add);
            }

            for (JsonNode polygon : polygonNodes) {
                if (!polygon.isArray() || polygon.isEmpty()) {
                    throw new BadRequestException("A polygon in that file has no rings.");
                }
                polygons++;
                boolean outer = true;
                for (JsonNode ring : polygon) {
                    List<double[]> points = readRing(ring);
                    rings.add(points);
                    vertices += points.size();
                    // Only the outer ring moves the box. A hole is inside its
                    // own polygon by definition, so including holes would be
                    // harmless but would also be arithmetic that means nothing.
                    if (outer) {
                        for (double[] p : points) {
                            minLat = Math.min(minLat, p[1]);
                            maxLat = Math.max(maxLat, p[1]);
                            minLng = Math.min(minLng, p[0]);
                            maxLng = Math.max(maxLng, p[0]);
                        }
                    }
                    outer = false;
                }
            }
        }

        if (vertices == 0) {
            throw new BadRequestException("That polygon has no points in it.");
        }
        return new GeoJsonPolygon(rings, polygons, minLat, maxLat, minLng, maxLng, vertices);
    }

    private static void collectGeometries(JsonNode node, List<JsonNode> into) {
        if (node == null || !node.isObject()) return;
        String type = node.path("type").asText("");
        switch (type) {
            case "Polygon", "MultiPolygon" -> into.add(node);
            case "Feature" -> collectGeometries(node.path("geometry"), into);
            case "FeatureCollection" -> node.path("features")
                    .forEach(feature -> collectGeometries(feature, into));
            case "GeometryCollection" -> node.path("geometries")
                    .forEach(geometry -> collectGeometries(geometry, into));
            default -> { /* a Point or a LineString has no inside; ignored here */ }
        }
    }

    private static List<double[]> readRing(JsonNode ring) {
        if (!ring.isArray() || ring.size() < 4) {
            // Four, not three: GeoJSON closes a ring by repeating its first
            // point, so the smallest legal triangle is four positions.
            throw new BadRequestException(
                    "A ring needs at least four positions and must close by repeating its first "
                            + "point. This one has " + (ring.isArray() ? ring.size() : 0) + ".");
        }
        List<double[]> points = new ArrayList<>(ring.size());
        for (JsonNode position : ring) {
            if (!position.isArray() || position.size() < 2) {
                throw new BadRequestException(
                        "Every position must be a [longitude, latitude] pair.");
            }
            double lng = position.get(0).asDouble();
            double lat = position.get(1).asDouble();
            if (lat < -90 || lat > 90) {
                throw new BadRequestException(
                        "Latitude " + lat + " is out of range. GeoJSON positions are "
                                + "[longitude, latitude] — if this file has them the other way "
                                + "round, the whole zone is somewhere it is not.");
            }
            if (lng < -180 || lng > 180) {
                throw new BadRequestException("Longitude " + lng + " is out of range.");
            }
            points.add(new double[] { lng, lat });
        }
        double[] first = points.get(0);
        double[] last = points.get(points.size() - 1);
        if (first[0] != last[0] || first[1] != last[1]) {
            // Closed here rather than refused: an unclosed ring is what several
            // drawing tools emit, and the fix is unambiguous.
            points.add(new double[] { first[0], first[1] });
        }
        return points;
    }

    /**
     * Whether a position falls inside the shape.
     *
     * <p>Even-odd: a point inside an odd number of rings is inside the polygon,
     * which is what makes holes work without being handled separately — a point
     * in a hole crosses the outer ring and the hole's ring, and two is even.
     */
    public boolean contains(double latitude, double longitude) {
        if (latitude < minLat || latitude > maxLat
                || longitude < minLng || longitude > maxLng) {
            return false;
        }
        int crossings = 0;
        for (List<double[]> ring : rings) {
            if (crossesRing(ring, latitude, longitude)) {
                crossings++;
            }
        }
        return crossings % 2 == 1;
    }

    private static boolean crossesRing(List<double[]> ring, double lat, double lng) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i)[0], yi = ring.get(i)[1];
            double xj = ring.get(j)[0], yj = ring.get(j)[1];
            boolean straddles = (yi > lat) != (yj > lat);
            if (straddles && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
