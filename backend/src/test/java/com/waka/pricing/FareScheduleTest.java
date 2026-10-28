package com.waka.pricing;

import com.waka.pricing.domain.FareBand;
import com.waka.pricing.domain.FareSchedule;
import com.waka.pricing.domain.PricingException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FareScheduleTest {
    @Test
    void noGapOverlapEarlyInclusiveOrMissingFinalBoundary() {
        assertInvalid(List.of(new FareBand("B01", 1, 17900, true, 150)));
        assertInvalid(List.of(new FareBand("B01", 0, 1000, false, 150),
                new FareBand("B02", 1001, 17900, true, 205)));
        assertInvalid(List.of(new FareBand("B01", 0, 1000, false, 150),
                new FareBand("B02", 999, 17900, true, 205)));
        assertInvalid(List.of(new FareBand("B01", 0, 1000, true, 150),
                new FareBand("B02", 1000, 17900, true, 205)));
        assertInvalid(List.of(new FareBand("B01", 0, 17899, true, 150)));
        assertInvalid(List.of(new FareBand("B01", 0, 17900, false, 150)));
        assertInvalid(List.of(new FareBand("B01", 0, 18000, true, 150)));
        assertInvalid(List.of(new FareBand("B01", 0, 1000, false, 150),
                new FareBand("B01", 1000, 17900, true, 205)));
        assertInvalid(List.of());
    }

    @Test
    void invalidBandPricesAndBoundsAreRejected() {
        assertThrows(PricingException.class, () -> new FareBand("B01", 0, 1000, false, 0));
        assertThrows(PricingException.class, () -> new FareBand("B01", 0, 1000, false, -150));
        assertThrows(PricingException.class, () -> new FareBand("B01", -1, 1000, false, 150));
        assertThrows(PricingException.class, () -> new FareBand("B01", 1000, 1000, false, 150));
        assertThrows(PricingException.class, () -> new FareBand("B01", 1001, 1000, false, 150));
        assertThrows(PricingException.class, () -> new FareBand(null, 0, 1000, false, 150));
    }

    @Test
    void snapshotIsImmutableAndVersionCurrencyAreRequired() {
        var source = new ArrayList<>(PricingOracle.schedule().bands());
        var schedule = new FareSchedule("launch-v1", "XAF", source);
        source.clear();
        assertEquals(19, schedule.bands().size());
        assertThrows(UnsupportedOperationException.class, () -> schedule.bands().clear());
        assertThrows(PricingException.class, () -> new FareSchedule("", "XAF", schedule.bands()));
        assertThrows(PricingException.class, () -> new FareSchedule("launch-v1", "EUR", schedule.bands()));
        assertThrows(PricingException.class, () -> new FareSchedule("launch-v1", "XAF", null));
    }

    private static void assertInvalid(List<FareBand> bands) {
        assertEquals(PricingException.Code.INVALID_FARE_SCHEDULE,
                assertThrows(PricingException.class,
                        () -> new FareSchedule("launch-v1", "XAF", bands)).code());
    }
}
