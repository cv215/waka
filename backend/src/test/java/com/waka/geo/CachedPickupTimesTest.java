package com.waka.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CachedPickupTimesTest {
    private static GeoPoint at(String lat, String lon) { return new GeoPoint(new BigDecimal(lat), new BigDecimal(lon)); }

    @Test void theSameRoadIsAskedOnceForFiveMinutesAndNoRouteIsNeverKept() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Integer> answer = new AtomicReference<>(300);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T10:00:00Z"));
        java.time.Clock clock = new java.time.Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var cached = new CachedPickupTimes((from, to) -> { calls.incrementAndGet(); return answer.get(); }, clock);
        assertEquals(300, cached.pickupSeconds(at("3.86671", "11.51672"), at("3.8935", "11.509")));
        assertEquals(300, cached.pickupSeconds(at("3.86668", "11.51669"), at("3.8935", "11.509")), "same place within 100 m");
        assertEquals(1, calls.get());
        now.set(now.get().plusSeconds(301));
        cached.pickupSeconds(at("3.86671", "11.51672"), at("3.8935", "11.509"));
        assertEquals(2, calls.get(), "asked again after five minutes");
        answer.set(null);
        assertNull(cached.pickupSeconds(at("3.9", "11.5"), at("3.8935", "11.509")));
        assertNull(cached.pickupSeconds(at("3.9", "11.5"), at("3.8935", "11.509")));
        assertEquals(4, calls.get(), "no route is asked again");
    }
}
