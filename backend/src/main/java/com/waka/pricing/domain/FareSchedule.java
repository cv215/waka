package com.waka.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;

/** Instantane immuable : aucune modification de grille ne change un calcul deja rendu. */
public record FareSchedule(String version, String currency, List<FareBand> bands) {
    public static final int LAST_SUPPORTED_METRE = 17_900;

    public FareSchedule {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || !"XAF".equals(currency) || bands == null || bands.isEmpty()
                || bands.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid();
        }
        bands = List.copyOf(bands);
        var ids = new HashSet<String>();
        int expectedLower = 0;
        for (int i = 0; i < bands.size(); i++) {
            var band = bands.get(i);
            boolean last = i == bands.size() - 1;
            if (!ids.add(band.id()) || band.lowerMetres() != expectedLower
                    || band.upperInclusive() != last
                    || band.upperMetres() > LAST_SUPPORTED_METRE) {
                throw invalid();
            }
            expectedLower = band.upperMetres();
        }
        if (expectedLower != LAST_SUPPORTED_METRE) { throw invalid(); }
    }

    /** Aucun double : les demi-metres restent representes exactement. */
    public int roundedDistance(BigDecimal rawMetres) {
        if (rawMetres == null || rawMetres.signum() < 0) {
            throw new PricingException(PricingException.Code.INVALID_DISTANCE);
        }
        // Comparer avant conversion evite tout debordement pour une tres grande distance.
        if (rawMetres.compareTo(new BigDecimal("17900.5")) >= 0) {
            throw new PricingException(PricingException.Code.DISTANCE_OUT_OF_RANGE);
        }
        return rawMetres.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    public FareBand bandAt(int roundedMetres) {
        if (roundedMetres < 0 || roundedMetres > LAST_SUPPORTED_METRE) {
            throw new PricingException(PricingException.Code.DISTANCE_OUT_OF_RANGE);
        }
        return bands.stream().filter(b -> b.contains(roundedMetres)).findFirst()
                .orElseThrow(FareSchedule::invalid);
    }

    private static PricingException invalid() {
        return new PricingException(PricingException.Code.INVALID_FARE_SCHEDULE);
    }
}
