package com.waka.pricing;

import com.waka.pricing.domain.FareCalculation;
import com.waka.pricing.domain.PricingEngine;
import com.waka.pricing.domain.PricingException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PricingEngineTest {
    private final PricingEngine engine = new PricingEngine();

    @ParameterizedTest(name = "{0}")
    @CsvFileSource(resources = "/pricing/07_oracles_repartitions.csv", numLinesToSkip = 1)
    void allIndependentAllocations(String caseId, String bandId, String mode, Integer paidSeats,
            boolean payerEligible, boolean driverEligible, int unitPrice,
            int multiplierNumerator, int multiplierDenominator, int referrals,
            String exactPrice, String exactCommission, long price, long commission, long driver,
            long payerBonus, long driverBonus, long waka) {
        var schedule = PricingOracle.schedule();
        var band = schedule.bands().stream().filter(b -> b.id().equals(bandId)).findFirst().orElseThrow();
        var distance = BigDecimal.valueOf(band.lowerMetres());
        var actual = "VIP".equals(mode)
                ? engine.vip(schedule, distance, payerEligible, driverEligible)
                : engine.shared(schedule, distance, paidSeats, payerEligible, driverEligible);
        assertAll(caseId,
                () -> assertEquals(unitPrice, actual.band().unitPriceXaf()),
                () -> assertEquals(multiplierNumerator, actual.priceMultiplierNumerator()),
                () -> assertEquals(multiplierDenominator, actual.priceMultiplierDenominator()),
                () -> assertEquals(referrals, actual.referralMultiplier()),
                () -> assertEquals("launch-v1", actual.fareVersion()),
                () -> assertEquals("XAF", actual.currency()),
                () -> assertEquals(FareCalculation.Mode.valueOf(mode), actual.mode()),
                () -> assertEquals(bandId, actual.band().id()),
                () -> assertEquals(band.lowerMetres(), actual.distanceMetres()),
                () -> assertEquals(payerEligible, actual.payerReferrerEligible()),
                () -> assertEquals(driverEligible, actual.driverReferrerEligible()),
                () -> assertEquals(price, actual.priceXaf()),
                () -> assertEquals(commission, actual.commissionXaf()),
                () -> assertEquals(driver, actual.driverXaf()),
                () -> assertEquals(payerBonus, actual.payerReferrerXaf()),
                () -> assertEquals(driverBonus, actual.driverReferrerXaf()),
                () -> assertEquals(waka, actual.wakaBeforeCostsXaf()));
        assertConservation(actual);
    }

    @ParameterizedTest(name = "{0}")
    @CsvFileSource(resources = "/pricing/07_oracles_distances.csv", numLinesToSkip = 1)
    void allIndependentDistances(String caseId, BigDecimal raw, int rounded,
            String zone, String bandId, Integer unitPrice) {
        var schedule = PricingOracle.schedule();
        if ("OUT_OF_ZONE".equals(zone)) {
            var ex = assertThrows(PricingException.class,
                    () -> engine.vip(schedule, raw, false, false), caseId);
            assertEquals(PricingException.Code.DISTANCE_OUT_OF_RANGE, ex.code());
        } else {
            var actual = engine.vip(schedule, raw, false, false);
            assertAll(caseId,
                    () -> assertEquals(rounded, actual.distanceMetres()),
                    () -> assertEquals(bandId, actual.band().id()),
                    () -> assertEquals(unitPrice.intValue(), actual.band().unitPriceXaf()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 6, Integer.MAX_VALUE})
    void sharedSeatsOutsideOneToFiveAreRejected(int seats) {
        var ex = assertThrows(PricingException.class,
                () -> engine.shared(PricingOracle.schedule(), BigDecimal.ZERO, seats, false, false));
        assertEquals(PricingException.Code.INVALID_SHARED_SEATS, ex.code());
    }

    @Test
    void invalidAndHugeDistancesAreRejectedWithoutOverflow() {
        var schedule = PricingOracle.schedule();
        for (var invalid : new BigDecimal[] {null, new BigDecimal("-0.0001")}) {
            assertEquals(PricingException.Code.INVALID_DISTANCE,
                    assertThrows(PricingException.class,
                            () -> engine.vip(schedule, invalid, false, false)).code());
        }
        for (var tooFar : new BigDecimal[] {new BigDecimal("17900.5"),
                new BigDecimal("999999999999999999999999999999999999999999999999"),
                new BigDecimal("1E+2147483647")}) {
            assertEquals(PricingException.Code.DISTANCE_OUT_OF_RANGE,
                    assertThrows(PricingException.class,
                            () -> engine.vip(schedule, tooFar, false, false)).code());
        }
    }

    @Test
    void roundingWitnessesKeepIntermediateAmountsExact() {
        var schedule = PricingOracle.schedule();
        var shared205 = engine.shared(schedule, new BigDecimal("1000"), 5, true, true);
        assertEquals(168, shared205.commissionXaf());
        assertEquals(1025, shared205.priceXaf());
        var vip205 = engine.vip(schedule, new BigDecimal("1000"), true, true);
        assertEquals(2563, vip205.priceXaf());
        assertEquals(421, vip205.commissionXaf());
        var vip600 = engine.vip(schedule, new BigDecimal("7000"), true, true);
        assertEquals(7500, vip600.priceXaf());
        assertEquals(750, vip600.commissionXaf());
        assertEquals(6750, vip600.driverXaf());
        assertEquals(100, vip600.payerReferrerXaf());
        assertEquals(50, vip600.driverReferrerXaf());
        assertEquals(600, vip600.wakaBeforeCostsXaf());
    }

    @Test
    void fixtureCoverageHasAllNineteenBandsAndFourEligibilityCases() {
        var rows = PricingOracle.rows("07_oracles_repartitions.csv");
        assertEquals(456, rows.size());
        assertEquals(114, rows.stream().map(r -> r[1] + ":" + r[2] + ":" + r[3]).distinct().count());
        assertEquals(19, PricingOracle.schedule().bands().size());
        assertEquals(176, PricingOracle.rows("07_oracles_distances.csv").size());
        var counts = rows.stream().collect(java.util.stream.Collectors.groupingBy(
                r -> r[1] + ":" + r[2] + ":" + r[3], java.util.stream.Collectors.counting()));
        assertTrue(counts.values().stream().allMatch(n -> n == 4));
        assertEquals(456, rows.stream().map(r -> r[1] + ":" + r[2] + ":" + r[3]
                + ":" + r[4] + ":" + r[5]).distinct().count());
    }

    static void assertConservation(FareCalculation actual) {
        assertEquals(actual.priceXaf(), actual.driverXaf() + actual.payerReferrerXaf()
                + actual.driverReferrerXaf() + actual.wakaBeforeCostsXaf());
        assertEquals(actual.commissionXaf(), actual.payerReferrerXaf()
                + actual.driverReferrerXaf() + actual.wakaBeforeCostsXaf());
        assertTrue(actual.priceXaf() >= 0 && actual.commissionXaf() >= 0 && actual.driverXaf() >= 0
                && actual.payerReferrerXaf() >= 0 && actual.driverReferrerXaf() >= 0
                && actual.wakaBeforeCostsXaf() >= 0);
    }
}
