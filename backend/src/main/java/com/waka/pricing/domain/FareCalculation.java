package com.waka.pricing.domain;

/** Calcul prepare pour un futur devis ; ce resultat ne vaut ni acceptation ni paiement. */
public record FareCalculation(String fareVersion, String currency, int distanceMetres,
        FareBand band, Mode mode, int priceMultiplierNumerator, int priceMultiplierDenominator,
        int referralMultiplier, boolean payerReferrerEligible, boolean driverReferrerEligible,
        long priceXaf, long commissionXaf, long driverXaf, long payerReferrerXaf,
        long driverReferrerXaf, long wakaBeforeCostsXaf) {
    public enum Mode { SHARED, VIP }

    public FareCalculation {
        if (priceXaf < 0 || commissionXaf < 0 || driverXaf < 0 || payerReferrerXaf < 0
                || driverReferrerXaf < 0 || wakaBeforeCostsXaf < 0
                || commissionXaf != payerReferrerXaf + driverReferrerXaf + wakaBeforeCostsXaf
                || priceXaf != driverXaf + commissionXaf) {
            throw new IllegalArgumentException("INVALID_FARE_ALLOCATION");
        }
    }
}
