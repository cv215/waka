package com.waka.geo;

/** The served territory is still to be established (R10): no implementation is assembled by default. */
public interface ServiceArea {
    boolean covers(GeoPoint point);
}
