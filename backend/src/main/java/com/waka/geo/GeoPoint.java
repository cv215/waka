package com.waka.geo;

import java.math.BigDecimal;

/**
 * A WGS84 point in exact decimal degrees, at most six decimals (about 11 cm). Never logged:
 * a precise point is personal data.
 */
public record GeoPoint(BigDecimal latitude, BigDecimal longitude) {
    private static final BigDecimal LAT = new BigDecimal(90), LON = new BigDecimal(180);

    public GeoPoint {
        if (latitude == null || longitude == null || latitude.scale() > 6 || longitude.scale() > 6
                || latitude.abs().compareTo(LAT) > 0 || longitude.abs().compareTo(LON) > 0) {
            throw new IllegalArgumentException("INVALID_POINT");
        }
        latitude = latitude.stripTrailingZeros();
        longitude = longitude.stripTrailingZeros();
    }

    /** Canonical text used for macs and encryption; equal points give equal text. */
    public String canonical() { return latitude.toPlainString() + "," + longitude.toPlainString(); }

    @Override public String toString() { return "GeoPoint[REDACTED]"; }
}
