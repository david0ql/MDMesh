package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class GeoMathTest {

    // A ~200 m square around (4.7000, -74.0500) in Bogotá: 0.0009° lat ≈ 100 m.
    private static final double[][] SQUARE = {
            {4.6991, -74.0509}, {4.6991, -74.0491}, {4.7009, -74.0491}, {4.7009, -74.0509}};

    @Test
    public void haversine_matches_known_distances() {
        // one degree of latitude ≈ 111.19 km
        assertEquals(111_195, GeoMath.distanceM(0, 0, 1, 0), 50);
        assertEquals(0, GeoMath.distanceM(4.7, -74.05, 4.7, -74.05), 0.001);
        // one degree of longitude at 60° N is half of that at the equator
        assertEquals(GeoMath.distanceM(0, 0, 0, 1) / 2, GeoMath.distanceM(60, 0, 60, 1), 100);
    }

    @Test
    public void circle_signed_distance_is_negative_inside() {
        assertEquals(-100, GeoMath.circleSignedDistanceM(4.7, -74.05, 100, 4.7, -74.05), 0.01);
        double d = GeoMath.circleSignedDistanceM(4.7, -74.05, 100, 4.7018, -74.05); // ~200 m north
        assertEquals(100, d, 2);
    }

    @Test
    public void point_in_polygon() {
        assertTrue(GeoMath.inPolygon(SQUARE, 4.7, -74.05));
        assertFalse(GeoMath.inPolygon(SQUARE, 4.702, -74.05));
        assertFalse(GeoMath.inPolygon(SQUARE, 4.7, -74.052));
        // closed ring (first vertex repeated) and reversed winding behave the same
        double[][] closed = {SQUARE[3], SQUARE[2], SQUARE[1], SQUARE[0], SQUARE[3]};
        assertTrue(GeoMath.inPolygon(closed, 4.7, -74.05));
        assertFalse(GeoMath.inPolygon(closed, 4.698, -74.05));
    }

    @Test
    public void point_in_concave_polygon() {
        // An "L": the notch (upper right quadrant) is outside.
        double[][] l = {{0, 0}, {0, 0.002}, {0.001, 0.002}, {0.001, 0.001}, {0.002, 0.001}, {0.002, 0}};
        assertTrue(GeoMath.inPolygon(l, 0.0005, 0.0015));
        assertTrue(GeoMath.inPolygon(l, 0.0015, 0.0005));
        assertFalse(GeoMath.inPolygon(l, 0.0015, 0.0015));
    }

    @Test
    public void polygon_signed_distance_in_metres() {
        assertEquals(-100, GeoMath.polygonSignedDistanceM(SQUARE, 4.7, -74.05), 3);
        // 0.0009° north of the top edge ≈ 100 m outside
        assertEquals(100, GeoMath.polygonSignedDistanceM(SQUARE, 4.7018, -74.05), 3);
        // on an edge ≈ 0
        assertEquals(0, GeoMath.polygonSignedDistanceM(SQUARE, 4.7009, -74.05), 1);
    }

    @Test
    public void polygon_inner_scale_is_radius_of_equal_area_circle() {
        // ~200 m x ~200 m => area 40 000 m2 => r ≈ 113 m
        assertEquals(113, GeoMath.polygonInnerScaleM(SQUARE), 4);
    }

    @Test
    public void parse_polygon_validates() {
        assertEquals(4, GeoMath.parsePolygon("[[4.6991,-74.0509],[4.6991,-74.0491],[4.7009,-74.0491],[4.7009,-74.0509]]").length);
        assertNull(GeoMath.parsePolygon("[[1,2],[3,4]]"));          // fewer than 3 vertices
        assertNull(GeoMath.parsePolygon("[[1,2],[3,4],[95,0]]"));    // latitude out of range
        assertNull(GeoMath.parsePolygon("nope"));
        assertNull(GeoMath.parsePolygon(null));
    }
}
