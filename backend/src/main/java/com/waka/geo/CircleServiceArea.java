package com.waka.geo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * R11: served cities as circles (centre and radius), free and without a maps service. A point is
 * served when it is within one circle; the great-circle distance is enough at city scale. Adding a
 * city is adding one circle.
 */
public final class CircleServiceArea implements ServiceArea {
    public record City(String code, GeoPoint centre, int radiusMetres) {
        public City {
            if (code == null || !code.matches("[A-Z]{3,12}") || centre == null || radiusMetres < 1_000 || radiusMetres > 50_000) {
                throw new IllegalArgumentException("INVALID_CITY");
            }
        }
    }
    private static final double EARTH_METRES = 6_371_008.8;
    /** R11 launch: Yaoundé, 20 km around the centre. */
    public static final CircleServiceArea LAUNCH = new CircleServiceArea(List.of(
            new City("YAOUNDE", new GeoPoint(new BigDecimal("3.848"), new BigDecimal("11.502")), 20_000)));

    private final List<City> cities;

    public CircleServiceArea(List<City> cities) {
        if (Objects.requireNonNull(cities).isEmpty()) throw new IllegalArgumentException("NO_CITY");
        this.cities = List.copyOf(cities);
    }

    @Override public boolean covers(GeoPoint point) { return city(point) != null; }

    /** The code of the city serving this point, or null outside every circle. */
    public String city(GeoPoint point) {
        for (City city : cities) if (metres(city.centre(), point) <= city.radiusMetres()) return city.code();
        return null;
    }

    static double metres(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.latitude().doubleValue()), lat2 = Math.toRadians(b.latitude().doubleValue());
        double dLat = lat2 - lat1, dLon = Math.toRadians(b.longitude().doubleValue() - a.longitude().doubleValue());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_METRES * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
