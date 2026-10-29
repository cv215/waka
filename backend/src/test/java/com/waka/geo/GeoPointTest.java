package com.waka.geo;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeoPointTest {
    @Test void boundsAndPrecisionAreExact() {
        new GeoPoint(new BigDecimal("90"), new BigDecimal("-180"));
        new GeoPoint(new BigDecimal("3.848123"), new BigDecimal("11.502"));
        for (String[] bad : new String[][] { { "90.000001", "0" }, { "0", "180.1" }, { "3.8481234", "11" } }) {
            assertThrows(IllegalArgumentException.class, () -> new GeoPoint(new BigDecimal(bad[0]), new BigDecimal(bad[1])));
        }
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(null, BigDecimal.ONE));
    }

    @Test void equalPointsHaveOneCanonicalTextAndAreNeverPrinted() {
        var a = new GeoPoint(new BigDecimal("3.8480"), new BigDecimal("11.50"));
        var b = new GeoPoint(new BigDecimal("3.848"), new BigDecimal("11.5"));
        assertEquals(a, b);
        assertEquals("3.848,11.5", a.canonical());
        assertEquals("GeoPoint[REDACTED]", a.toString());
    }
}
