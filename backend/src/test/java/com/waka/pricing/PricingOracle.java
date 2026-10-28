package com.waka.pricing;

import com.waka.pricing.domain.FareBand;
import com.waka.pricing.domain.FareSchedule;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Donnees attendues preparees separement AVANT le moteur, sans recalcul d'oracle. */
final class PricingOracle {
    private PricingOracle() { }

    static List<String[]> rows(String resource) {
        try (var input = PricingOracle.class.getResourceAsStream("/pricing/" + resource)) {
            if (input == null) { throw new IllegalStateException("MISSING_ORACLE"); }
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines().skip(1).filter(line -> !line.isBlank())
                        .map(line -> line.split(",", -1)).toList();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("UNREADABLE_ORACLE");
        }
    }

    static FareSchedule schedule() {
        var bands = rows("07_oracles_bands.csv").stream().map(row -> new FareBand(
                row[0], Integer.parseInt(row[1]), Integer.parseInt(row[2]),
                Boolean.parseBoolean(row[3]), Integer.parseInt(row[4]))).toList();
        return new FareSchedule("launch-v1", "XAF", bands);
    }
}
