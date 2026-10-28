package com.waka.pricing.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** R03/R04 uniquement. Aucun acces reseau, compte, paiement ou droit accorde ici. */
public final class PricingEngine {
    public FareCalculation shared(FareSchedule schedule, BigDecimal distanceMetres, int paidSeats,
            boolean payerReferrerEligible, boolean driverReferrerEligible) {
        if (paidSeats < 1 || paidSeats > 5) {
            throw new PricingException(PricingException.Code.INVALID_SHARED_SEATS);
        }
        return calculate(schedule, distanceMetres, FareCalculation.Mode.SHARED, paidSeats, 1,
                paidSeats, payerReferrerEligible, driverReferrerEligible);
    }

    // Aucun nombre d'occupants : le VIP vaut toujours 25/2 fois une place.
    public FareCalculation vip(FareSchedule schedule, BigDecimal distanceMetres,
            boolean payerReferrerEligible, boolean driverReferrerEligible) {
        return calculate(schedule, distanceMetres, FareCalculation.Mode.VIP, 25, 2,
                10, payerReferrerEligible, driverReferrerEligible);
    }

    private FareCalculation calculate(FareSchedule schedule, BigDecimal rawMetres,
            FareCalculation.Mode mode, int numerator, int denominator, int referrals,
            boolean payerEligible, boolean driverEligible) {
        Objects.requireNonNull(schedule, "fareSchedule");
        int metres = schedule.roundedDistance(rawMetres);
        FareBand band = schedule.bandAt(metres);
        // p est un int positif ; ces produits longs restent tres inferieurs a Long.MAX_VALUE.
        long price = halfUp((long) numerator * band.unitPriceXaf(), denominator);
        long commission = halfUp((long) numerator * (band.unitPriceXaf() + 300L), denominator * 15L);
        long payerBonus = payerEligible ? 10L * referrals : 0;
        long driverBonus = driverEligible ? 5L * referrals : 0;
        return new FareCalculation(schedule.version(), schedule.currency(), metres, band, mode,
                numerator, denominator, referrals, payerEligible, driverEligible,
                price, commission, price - commission, payerBonus, driverBonus,
                commission - payerBonus - driverBonus);
    }

    /** Division rationnelle exacte, arrondie une seule fois au franc final. */
    private static long halfUp(long numerator, long denominator) {
        return numerator / denominator + (2 * (numerator % denominator) >= denominator ? 1 : 0);
    }
}
