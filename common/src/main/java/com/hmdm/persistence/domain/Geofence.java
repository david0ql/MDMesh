package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hmdm.util.GeoMath;
import com.hmdm.util.GeofenceEvaluator;

import java.io.Serializable;
import java.util.List;

/**
 * DallyControl — a geofence (table {@code geofence}): a circle or polygon with a rule ("must stay inside" /
 * "must not enter"), an optional schedule and who it applies to. Public fields: it is both the MyBatis row and the
 * console's JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Geofence implements Serializable {
    private static final long serialVersionUID = 1L;

    public Integer id;
    @JsonIgnore
    public int customerId;
    public String name;
    public String color;
    /** "circle" or "polygon". */
    public String shape;
    public Double centerLat;
    public Double centerLon;
    public Double radiusM;
    /** Stored as JSON text {@code [[lat,lon],…]}; the console sees it as {@code points}. */
    @JsonIgnore
    public String polygon;
    /** "stayInside" or "keepOut". */
    public String rule;
    public boolean active = true;
    public boolean alertOnReturn = true;
    /** Applies to every device of the customer (only a full administrator may set it). */
    public boolean allDevices;
    /** JSON text, see {@link GeofenceEvaluator.Schedule}; null = always. */
    public String schedule;
    /** Extra recipients of this zone's alert e-mails (comma separated). */
    public String emails;
    /** While this zone is active, its devices report a position at least this often (minutes); null = policy's own. */
    public Integer trackingMinutes;
    public long createdAt;
    public long updatedAt;

    // Assignments (not columns): filled by the DAO / sent by the console.
    public List<Integer> groupIds;
    public List<Integer> deviceIds;

    @JsonProperty("points")
    public double[][] getPoints() {
        return GeoMath.parsePolygon(polygon);
    }

    @JsonProperty("points")
    public void setPoints(double[][] points) {
        this.polygon = points == null ? null : GeoMath.polygonJson(points);
    }

    /** The evaluator's view of this zone, or null when its geometry is unusable. */
    public GeofenceEvaluator.Zone toZone() {
        GeofenceEvaluator.Zone z = new GeofenceEvaluator.Zone();
        z.shape = shape;
        z.rule = rule;
        z.alertOnReturn = alertOnReturn;
        z.schedule = GeofenceEvaluator.Schedule.parse(schedule);
        if ("polygon".equals(shape)) {
            z.polygon = GeoMath.parsePolygon(polygon);
            if (z.polygon == null) return null;
        } else {
            if (centerLat == null || centerLon == null || radiusM == null || radiusM <= 0) return null;
            z.lat = centerLat;
            z.lon = centerLon;
            z.radiusM = radiusM;
        }
        return z;
    }
}
