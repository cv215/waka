package com.waka.geo;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Road times kept for a few minutes, keyed by both points rounded to about 100 m: the client's screen polls the
 * search every few seconds and each poll weighs the same drivers again, which must not cost a maps call each time.
 * A missing route or a failure is never kept, so it is asked again next time.
 */
public final class CachedPickupTimes implements PickupTimeProvider {
    public static final Duration KEEP = Duration.ofMinutes(5);
    private static final int SIZE = 2000;

    private record Kept(int seconds, Instant at) { }

    private final PickupTimeProvider provider;
    private final Clock clock;
    private final Map<String, Kept> kept = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) { return size() > SIZE; }
    });

    public CachedPickupTimes(PickupTimeProvider provider, Clock clock) {
        this.provider = Objects.requireNonNull(provider);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Integer pickupSeconds(GeoPoint from, GeoPoint to) throws RouteDistanceProvider.RouteUnavailableException {
        if (from == null || to == null) throw new IllegalArgumentException("INVALID_POINT");
        String key = rounded(from) + ";" + rounded(to);
        Instant now = clock.instant();
        Kept known = kept.get(key);
        if (known != null && now.isBefore(known.at().plus(KEEP))) return known.seconds();
        Integer seconds = provider.pickupSeconds(from, to);
        if (seconds != null) kept.put(key, new Kept(seconds, now));
        return seconds;
    }

    private static String rounded(GeoPoint p) {
        return p.latitude().setScale(3, RoundingMode.HALF_UP).toPlainString() + "," + p.longitude().setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    @Override public String toString() { return "CachedPickupTimes[REDACTED]"; }
}
