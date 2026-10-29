package com.waka.geo;

import java.math.BigDecimal;

/**
 * R04 reference road distance, established by the server. The provider (maps service) is an
 * external API kept for the end of the project: no implementation is assembled by default.
 */
public interface RouteDistanceProvider {
    /** Road distance in metres, or {@code null} when no route exists between the points. */
    BigDecimal roadDistanceMetres(GeoPoint origin, GeoPoint destination) throws RouteUnavailableException;

    /** The provider could not answer reliably; nothing is guessed from the points. */
    final class RouteUnavailableException extends Exception {
        public RouteUnavailableException() { super("ROUTE_UNAVAILABLE", null, false, false); }
    }
}
