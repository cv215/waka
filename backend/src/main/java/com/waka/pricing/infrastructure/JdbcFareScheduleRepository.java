package com.waka.pricing.infrastructure;

import com.waka.pricing.domain.FareBand;
import com.waka.pricing.domain.FareSchedule;
import com.waka.pricing.domain.PricingException;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lecture seule, version explicite ; aucune recherche de grille « active » implicite. */
@Repository
// Spring traduit les exceptions @Repository au moyen d'un proxy de sous-classe.
// La classe reste donc extensible ; l'instantane FareSchedule reste immuable.
public class JdbcFareScheduleRepository {
    private final JdbcTemplate jdbc;

    public JdbcFareScheduleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public FareSchedule findByVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new PricingException(PricingException.Code.UNKNOWN_FARE_VERSION);
        }
        // Une requete liee et un seul resultat coherent, sans concatenation SQL.
        return jdbc.query("""
                SELECT s.version, s.currency, b.band_code, b.lower_metres,
                       b.upper_metres, b.upper_inclusive, b.unit_price_xaf
                FROM fare_schedule s
                LEFT JOIN fare_band b ON b.schedule_version = s.version
                WHERE s.version = ?
                ORDER BY b.lower_metres
                """, rs -> {
            var bands = new ArrayList<FareBand>();
            String foundVersion = null;
            String currency = null;
            while (rs.next()) {
                foundVersion = rs.getString("version");
                currency = rs.getString("currency");
                if (rs.getString("band_code") != null) {
                    bands.add(new FareBand(rs.getString("band_code"), rs.getInt("lower_metres"),
                            rs.getInt("upper_metres"), rs.getBoolean("upper_inclusive"),
                            rs.getInt("unit_price_xaf")));
                }
            }
            if (foundVersion == null) {
                throw new PricingException(PricingException.Code.UNKNOWN_FARE_VERSION);
            }
            return new FareSchedule(foundVersion, currency, bands);
        }, version);
    }
}
