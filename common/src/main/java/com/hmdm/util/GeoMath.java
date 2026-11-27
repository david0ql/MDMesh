package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DallyControl — geometry for geofences (pure, no dependencies beyond Jackson for parsing).
 *
 * Zones are small compared with the Earth, so polygons are evaluated on a local flat projection (metres east/north
 * of the tested point); circles use the haversine distance. A "signed distance" is negative inside the zone and
 * positive outside, in metres from its border.
 */
public final class GeoMath {

    private static final double EARTH_RADIUS_M = 6_371_008.8;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** A polygon drawn by hand never needs more; bounds the cost of evaluating one fix. */
    public static final int MAX_VERTICES = 200;

    private GeoMath() {
    }

    /** Great-circle distance in metres. */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static double circleSignedDistanceM(double centerLat, double centerLon, double radiusM, double lat, double lon) {
        return distanceM(centerLat, centerLon, lat, lon) - radiusM;
    }

    /** Vertices as metres east (x) / north (y) of the origin point. */
    private static double[][] project(double[][] polygon, double lat0, double lon0) {
        double k = Math.cos(Math.toRadians(lat0));
        double[][] out = new double[polygon.length][2];
        for (int i = 0; i < polygon.length; i++) {
            double dLon = polygon[i][1] - lon0;
            if (dLon > 180) dLon -= 360;
            if (dLon < -180) dLon += 360;
            out[i][0] = Math.toRadians(dLon) * k * EARTH_RADIUS_M;
            out[i][1] = Math.toRadians(polygon[i][0] - lat0) * EARTH_RADIUS_M;
        }
        return out;
    }

    /** Ray casting; {@code polygon} is a list of [lat, lon] vertices (open or closed ring, any winding). */
    public static boolean inPolygon(double[][] polygon, double lat, double lon) {
        return originInside(project(polygon, lat, lon));
    }

    private static boolean originInside(double[][] p) {
        boolean inside = false;
        for (int i = 0, j = p.length - 1; i < p.length; j = i++) {
            double xi = p[i][0], yi = p[i][1], xj = p[j][0], yj = p[j][1];
            if ((yi > 0) != (yj > 0) && 0 < (xj - xi) * (0 - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }

    /** Metres to the polygon's border: negative inside, positive outside. */
    public static double polygonSignedDistanceM(double[][] polygon, double lat, double lon) {
        double[][] p = project(polygon, lat, lon);
        double best = Double.MAX_VALUE;
        for (int i = 0, j = p.length - 1; i < p.length; j = i++) {
            best = Math.min(best, originToSegment(p[j][0], p[j][1], p[i][0], p[i][1]));
        }
        return originInside(p) ? -best : best;
    }

    private static double originToSegment(double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (-ax * dx - ay * dy) / len2));
        double x = ax + t * dx, y = ay + t * dy;
        return Math.sqrt(x * x + y * y);
    }

    /** Radius of the circle with the polygon's area: how "deep" the zone is, to cap the accuracy margin on small zones. */
    public static double polygonInnerScaleM(double[][] polygon) {
        double[][] p = project(polygon, polygon[0][0], polygon[0][1]);
        double a = 0;
        for (int i = 0, j = p.length - 1; i < p.length; j = i++) a += p[j][0] * p[i][1] - p[i][0] * p[j][1];
        return Math.sqrt(Math.abs(a) / 2 / Math.PI);
    }

    /** {@code [[lat,lon],…]} with 3–{@value #MAX_VERTICES} valid vertices, or null when it is not a usable polygon. */
    public static double[][] parsePolygon(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray() || root.size() < 3 || root.size() > MAX_VERTICES) return null;
            double[][] out = new double[root.size()][2];
            for (int i = 0; i < root.size(); i++) {
                JsonNode v = root.get(i);
                if (!v.isArray() || v.size() != 2 || !v.get(0).isNumber() || !v.get(1).isNumber()) return null;
                double lat = v.get(0).asDouble(), lon = v.get(1).asDouble();
                if (!validPoint(lat, lon)) return null;
                out[i][0] = lat;
                out[i][1] = lon;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean validPoint(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    /** Canonical JSON for a parsed polygon (what is stored). */
    public static String polygonJson(double[][] polygon) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < polygon.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(polygon[i][0]).append(',').append(polygon[i][1]).append(']');
        }
        return sb.append(']').toString();
    }
}
