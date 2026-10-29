package com.waka.geo;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircleServiceAreaTest {
    private static GeoPoint p(String lat, String lon) { return new GeoPoint(new BigDecimal(lat), new BigDecimal(lon)); }

    @Test void yaoundeIsServedDoualaIsNot() {
        var area = CircleServiceArea.LAUNCH;
        assertEquals("YAOUNDE", area.city(p("3.848", "11.502")), "centre");
        assertTrue(area.covers(p("3.87", "11.52")), "a few km away");
        assertTrue(area.covers(p("3.95", "11.55")), "about 12 km north");
        assertFalse(area.covers(p("4.05", "9.7")), "Douala");
        assertFalse(area.covers(p("3.52", "11.5")), "Mbalmayo, about 36 km south");
    }

    @Test void theRadiusIsAboutTwentyKilometres() {
        // 0.18 degree of latitude is about 20.0 km; 0.17 is about 18.9 km.
        assertTrue(CircleServiceArea.LAUNCH.covers(p("4.018", "11.502")));
        assertFalse(CircleServiceArea.LAUNCH.covers(p("4.03", "11.502")));
        assertEquals(20_015, CircleServiceArea.metres(p("3.848", "11.502"), p("4.028", "11.502")), 30);
    }

    @Test void anotherCityIsOneMoreCircle() {
        var area = new CircleServiceArea(List.of(new CircleServiceArea.City("YAOUNDE", p("3.848", "11.502"), 20_000),
                new CircleServiceArea.City("DOUALA", p("4.05", "9.7"), 20_000)));
        assertEquals("DOUALA", area.city(p("4.06", "9.71")));
        assertThrows(IllegalArgumentException.class, () -> new CircleServiceArea.City("X", p("0", "0"), 20_000));
        assertThrows(IllegalArgumentException.class, () -> new CircleServiceArea(List.of()));
    }
}
