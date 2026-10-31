package com.waka.mobility.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waka.geo.GeoPoint;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TourOrderTest {
    private static GeoPoint at(String lat, String lon) { return new GeoPoint(new BigDecimal(lat), new BigDecimal(lon)); }
    private static PersistentRideService.StopView stop(String kind, UUID ride, GeoPoint p) { return new PersistentRideService.StopView(kind, ride, p); }

    @Test void nearestFirstButNeverADropOffBeforeItsPickup() {
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        // Client one's drop-off is the nearest point, yet his pickup comes first; client two is already on board.
        var tour = PersistentRideService.order(at("3.800", "11.500"), List.of(
                stop("pickup", one, at("3.830", "11.500")),
                stop("dropoff", one, at("3.801", "11.500")),
                stop("dropoff", two, at("3.820", "11.500"))));
        assertEquals(List.of("dropoff:" + two, "pickup:" + one, "dropoff:" + one),
                tour.stream().map(s -> s.kind() + ":" + s.reservationId()).toList());
    }
}
