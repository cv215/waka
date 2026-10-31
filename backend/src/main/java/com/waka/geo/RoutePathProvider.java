package com.waka.geo;

import java.util.List;

/**
 * The drawn shape of a road route through ordered stops (first = start, last = end), as an encoded polyline
 * (Google's format). Only for display: distances and prices never come from it.
 */
public interface RoutePathProvider {
    /** Encoded polyline, or {@code null} when no road joins the stops. */
    String encodedPath(List<GeoPoint> stops) throws RouteDistanceProvider.RouteUnavailableException;

    /** Without a maps provider: straight segments between the stops (local trials only). */
    RoutePathProvider STRAIGHT = Polylines::encode;
}
