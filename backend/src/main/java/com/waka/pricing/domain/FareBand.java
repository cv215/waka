package com.waka.pricing.domain;

/** Bornes en metres entiers ; seule la derniere tranche inclut sa borne haute. */
public record FareBand(String id, int lowerMetres, int upperMetres,
                       boolean upperInclusive, int unitPriceXaf) {
    public FareBand {
        if (id == null || !id.matches("B[0-9]{2}") || lowerMetres < 0
                || upperMetres <= lowerMetres || unitPriceXaf <= 0) {
            throw new PricingException(PricingException.Code.INVALID_FARE_SCHEDULE);
        }
    }

    public boolean contains(int metres) {
        return metres >= lowerMetres && (metres < upperMetres
                || (upperInclusive && metres == upperMetres));
    }
}
