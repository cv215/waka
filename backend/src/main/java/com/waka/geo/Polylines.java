package com.waka.geo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;

/** Google's encoded polyline format, five decimals: encoding of points, and the shape check of a received path. */
public final class Polylines {
    /** Printable ASCII from '?' (63) to '~' (126): the alphabet of an encoded polyline. */
    private static final Pattern VALID = Pattern.compile("[\\x3F-\\x7E]{2,20000}");

    private Polylines() { }

    public static String encode(List<GeoPoint> points) {
        if (points == null || points.size() < 2) throw new IllegalArgumentException("PATH_POINTS");
        StringBuilder out = new StringBuilder();
        long lastLat = 0, lastLon = 0;
        for (GeoPoint p : points) {
            long lat = e5(p.latitude()), lon = e5(p.longitude());
            value(out, lat - lastLat);
            value(out, lon - lastLon);
            lastLat = lat;
            lastLon = lon;
        }
        return out.toString();
    }

    public static boolean valid(String encoded) { return encoded != null && VALID.matcher(encoded).matches(); }

    private static long e5(BigDecimal degrees) { return degrees.movePointRight(5).setScale(0, RoundingMode.HALF_UP).longValueExact(); }

    private static void value(StringBuilder out, long delta) {
        long v = delta < 0 ? ~(delta << 1) : delta << 1;
        while (v >= 0x20) {
            out.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        out.append((char) (v + 63));
    }
}
