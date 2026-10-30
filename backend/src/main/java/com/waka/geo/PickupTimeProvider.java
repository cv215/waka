package com.waka.geo;

/**
 * R13: road time from a driver's position to the pickup point, from the maps provider (external API kept
 * for the end of the project). Never replaced by a straight-line distance. No implementation is assembled.
 */
public interface PickupTimeProvider {
    /** Seconds by road, or {@code null} when no route exists. */
    Integer pickupSeconds(GeoPoint driver, GeoPoint pickup) throws RouteDistanceProvider.RouteUnavailableException;
}