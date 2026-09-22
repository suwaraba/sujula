package com.sujula.service.delivery;

import org.junit.jupiter.api.Test;

import com.sujula.exceptions.BadRequestException;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Polygons, and the one mistake that would otherwise fail silently.
 *
 * <p>A zone whose coordinates are the wrong way round is a zone in the Atlantic.
 * It parses, it stores, it contains nothing, and nobody finds out until a month
 * of parcels have quietly failed to be quotable — so the refusal for it has a
 * test of its own.
 */
class GeoJsonPolygonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** A rough box around Serrekunda, in GeoJSON's own [lng, lat] order. */
    private static final String SERREKUNDA = """
            {"type":"Polygon","coordinates":[[
              [-16.72,13.42],[-16.66,13.42],[-16.66,13.47],[-16.72,13.47],[-16.72,13.42]
            ]]}""";

    @Test
    void aPointInsideTheBoxIsInsideTheZone() {
        GeoJsonPolygon shape = GeoJsonPolygon.parse(SERREKUNDA, mapper);
        assertTrue(shape.contains(13.44, -16.69));
    }

    @Test
    void aPointInBanjulIsOutsideASerrekundaZone() {
        GeoJsonPolygon shape = GeoJsonPolygon.parse(SERREKUNDA, mapper);
        assertFalse(shape.contains(13.4549, -16.5790));
    }

    @Test
    void theBoundingBoxIsMeasuredFromTheOuterRing() {
        GeoJsonPolygon shape = GeoJsonPolygon.parse(SERREKUNDA, mapper);
        assertEquals(13.42, shape.minLatitude(), 0.0001);
        assertEquals(13.47, shape.maxLatitude(), 0.0001);
        assertEquals(-16.72, shape.minLongitude(), 0.0001);
        assertEquals(-16.66, shape.maxLongitude(), 0.0001);
        assertEquals(5, shape.vertexCount());
    }

    @Test
    void aSwappedPairThatPutsLatitudeOffTheEarthIsRefusedByName() {
        // Madrid: 40.4N, 3.7W. Swapped, the latitude becomes -3.7 and the
        // longitude 40.4 — both legal. But Stockholm at 59.3N, 18.1E swapped
        // gives a longitude of 59.3 and a latitude of 18.1, also legal. The
        // range check only bites where the longitude is beyond ±90, which is
        // the Americas, Asia and the Pacific — so here is one of those.
        String pacific = """
                {"type":"Polygon","coordinates":[[
                  [21.3,-157.8],[21.4,-157.8],[21.4,-157.7],[21.3,-157.7],[21.3,-157.8]
                ]]}""";
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> GeoJsonPolygon.parse(pacific, mapper));
        assertTrue(refused.getMessage().contains("[longitude, latitude]"),
                "the message has to name the mistake: " + refused.getMessage());
    }

    @Test
    void aSwapTooSmallForTheRangeCheckStillParsesHereAndIsCaughtByTheCountryBox() {
        // The Serrekunda box with latitude first. Both values are small, so
        // nothing here can tell — 13.42 is a legal longitude and -16.72 a legal
        // latitude. It parses, it stores, and it contains no Gambian address.
        // Catching it is CountryBounds' job, which is why that class exists.
        String swapped = """
                {"type":"Polygon","coordinates":[[
                  [13.42,-16.72],[13.42,-16.66],[13.47,-16.66],[13.47,-16.72],[13.42,-16.72]
                ]]}""";
        GeoJsonPolygon shape = GeoJsonPolygon.parse(swapped, mapper);
        assertFalse(shape.contains(13.44, -16.69), "a Serrekunda address is not in it");
        assertFalse(CountryBounds.of("GM").orElseThrow().overlaps(
                        shape.minLatitude(), shape.maxLatitude(),
                        shape.minLongitude(), shape.maxLongitude()),
                "and the country box says so");
    }

    @Test
    void aHoleIsOutsideTheZoneEvenThoughItIsInsideTheOuterRing() {
        String withHole = """
                {"type":"Polygon","coordinates":[
                  [[-17.0,13.0],[-16.0,13.0],[-16.0,14.0],[-17.0,14.0],[-17.0,13.0]],
                  [[-16.6,13.4],[-16.4,13.4],[-16.4,13.6],[-16.6,13.6],[-16.6,13.4]]
                ]}""";
        GeoJsonPolygon shape = GeoJsonPolygon.parse(withHole, mapper);
        assertTrue(shape.contains(13.2, -16.8), "inside the outer ring, outside the hole");
        assertFalse(shape.contains(13.5, -16.5), "inside the hole, so outside the zone");
    }

    @Test
    void aFeatureCollectionFromADrawingToolIsReadRatherThanRefused() {
        // What QGIS and geojson.io actually export. Telling an administrator
        // their own export is invalid is not a useful error.
        String feature = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"name":"Kanifing"},
                   "geometry":%s}
                ]}""".formatted(SERREKUNDA);
        GeoJsonPolygon shape = GeoJsonPolygon.parse(feature, mapper);
        assertTrue(shape.contains(13.44, -16.69));
        assertEquals(1, shape.polygonCount());
    }

    @Test
    void anUnclosedRingIsClosedRatherThanRefused() {
        String open = """
                {"type":"Polygon","coordinates":[[
                  [-16.72,13.42],[-16.66,13.42],[-16.66,13.47],[-16.72,13.47]
                ]]}""";
        GeoJsonPolygon shape = GeoJsonPolygon.parse(open, mapper);
        // The fix is unambiguous and several tools emit this, so it is repaired
        // rather than rejected — but the repaired ring is a real ring.
        assertEquals(5, shape.vertexCount());
        assertTrue(shape.contains(13.44, -16.69));
    }

    @Test
    void aPointGeometryIsRefusedBecauseItHasNoInside() {
        String point = """
                {"type":"Point","coordinates":[-16.69,13.44]}""";
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> GeoJsonPolygon.parse(point, mapper));
        assertTrue(refused.getMessage().contains("no inside"));
    }

    @Test
    void aMultiPolygonCoversBothOfItsParts() {
        String twoIslands = """
                {"type":"MultiPolygon","coordinates":[
                  [[[-16.72,13.42],[-16.66,13.42],[-16.66,13.47],[-16.72,13.47],[-16.72,13.42]]],
                  [[[-16.60,13.44],[-16.55,13.44],[-16.55,13.48],[-16.60,13.48],[-16.60,13.44]]]
                ]}""";
        GeoJsonPolygon shape = GeoJsonPolygon.parse(twoIslands, mapper);
        assertEquals(2, shape.polygonCount());
        assertTrue(shape.contains(13.44, -16.69), "in the first part");
        assertTrue(shape.contains(13.46, -16.57), "in the second part");
        assertFalse(shape.contains(13.46, -16.63), "in the gap between them");
    }

    @Test
    void nothingIsInsideAShapeThatWasNeverGiven() {
        assertThrows(BadRequestException.class, () -> GeoJsonPolygon.parse(null, mapper));
        assertThrows(BadRequestException.class, () -> GeoJsonPolygon.parse("   ", mapper));
        assertThrows(BadRequestException.class, () -> GeoJsonPolygon.parse("not json", mapper));
    }
}
