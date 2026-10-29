package com.waka.mobility.voyage;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** R11 examples computed by hand, never by the code under test. */
class VoyageRulesTest {
    private final Instant departure = Instant.parse("2026-11-15T06:00:00Z");

    @Test void boardingPaysTheDriverNinetyPercent() {
        assertEquals(new VoyageRules.Settlement(5000, 0, 4500, 500, 0), VoyageRules.boarding(5000));
        assertEquals(new VoyageRules.Settlement(10000, 0, 9000, 1000, 0), VoyageRules.boarding(10000));
        // 2 345 x 10 % = 234.5, rounded half up to 235.
        assertEquals(new VoyageRules.Settlement(2345, 0, 2110, 235, 0), VoyageRules.boarding(2345));
        assertEquals(new VoyageRules.Settlement(1, 0, 1, 0, 0), VoyageRules.boarding(1));
    }

    @Test void driverCancellationRefundsEverything() {
        assertEquals(new VoyageRules.Settlement(7000, 7000, 0, 0, 0), VoyageRules.cancelledByDriver(7000));
    }

    @Test void clientCancellationTiers() {
        assertEquals(new VoyageRules.Settlement(6000, 6000, 0, 0, 0),
                VoyageRules.cancelledByClient(6000, departure, departure.minus(Duration.ofDays(2))));
        assertEquals(0, VoyageRules.cancelledByClient(6000, departure, departure.minus(Duration.ofHours(12))).feePercent(),
                "exactly 12 h: the cheaper tier");
        // 11 h 59 before: 15 % of 6 000 = 900, split 450 / 450.
        assertEquals(new VoyageRules.Settlement(6000, 5100, 450, 450, 15),
                VoyageRules.cancelledByClient(6000, departure, departure.minus(Duration.ofMinutes(719))));
        assertEquals(15, VoyageRules.cancelledByClient(6000, departure, departure.minus(Duration.ofHours(3))).feePercent());
        // Under 3 h: 30 % of 6 000 = 1 800, split 900 / 900.
        assertEquals(new VoyageRules.Settlement(6000, 4200, 900, 900, 30),
                VoyageRules.cancelledByClient(6000, departure, departure.minus(Duration.ofMinutes(179))));
        assertEquals(30, VoyageRules.cancelledByClient(6000, departure, departure.plusSeconds(60)).feePercent(), "after departure, not boarded");
    }

    @Test void oddFeesAreSplitWithoutLosingAFranc() {
        // 15 % of 2 345 = 351.75 -> 352; driver 176, WAKA 176; refund 1 993.
        assertEquals(new VoyageRules.Settlement(2345, 1993, 176, 176, 15),
                VoyageRules.cancelledByClient(2345, departure, departure.minus(Duration.ofHours(5))));
        // 30 % of 2 345 = 703.5 -> 704; driver 352, WAKA 352; refund 1 641.
        assertEquals(new VoyageRules.Settlement(2345, 1641, 352, 352, 30),
                VoyageRules.cancelledByClient(2345, departure, departure.minus(Duration.ofHours(1))));
        // 15 % of 1 001 = 150.15 -> 150; 30 % of 1 001 = 300.3 -> 300; 15 % of 1 003 = 150.45 -> 150; 30 % of 1 005 = 301.5 -> 302.
        assertEquals(150, VoyageRules.cancelledByClient(1001, departure, departure.minus(Duration.ofHours(5))).driverXaf()
                + VoyageRules.cancelledByClient(1001, departure, departure.minus(Duration.ofHours(5))).wakaXaf());
        var odd = VoyageRules.cancelledByClient(1005, departure, departure.minus(Duration.ofHours(1)));
        assertEquals(151, odd.driverXaf());
        assertEquals(151, odd.wakaXaf());
        var oddFee = VoyageRules.cancelledByClient(1003, departure, departure.minus(Duration.ofHours(1)));
        // 30 % of 1 003 = 300.9 -> 301: driver 150 (half rounded down), WAKA 151.
        assertEquals(150, oddFee.driverXaf());
        assertEquals(151, oddFee.wakaXaf());
    }

    @Test void priceListNamesEveryPairWithinTheCap() {
        assertEquals(List.of("0-1", "0-2", "1-2"), VoyageRules.pairs(3));
        Map<String, Integer> prices = new HashMap<>(Map.of("0-1", 3000, "0-2", 7000, "1-2", 4500));
        assertTrue(VoyageRules.validPrices(3, prices));
        prices.put("0-2", 10_001);
        assertFalse(VoyageRules.validPrices(3, prices), "above 10 000 F a seat");
        prices.put("0-2", 0);
        assertFalse(VoyageRules.validPrices(3, prices));
        prices.remove("0-2");
        assertFalse(VoyageRules.validPrices(3, prices), "a missing pair");
        assertFalse(VoyageRules.validPrices(1, Map.of()));
        assertFalse(VoyageRules.validPrices(7, Map.of()), "at most six stops");
        assertThrows(IllegalArgumentException.class, () -> VoyageRules.boarding(0));
    }
}
