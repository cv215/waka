package com.waka.pricing;

import com.waka.pricing.domain.PricingEngine;
import com.waka.pricing.domain.PricingException;
import com.waka.pricing.infrastructure.JdbcFareScheduleRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Vraie BD, en lecture seule ; les migrations sont executees SEPAREMENT par le coordinateur. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class PricingMySqlVerificationTest {
    @Test
    void realMigratedMySqlMatchesIndependentOracleWithoutWrites() {
        var datasource = new DriverManagerDataSource();
        datasource.setUrl(required("WAKA_DB_URL"));
        datasource.setUsername(required("WAKA_DB_USER"));
        datasource.setPassword(required("WAKA_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(datasource);
        var repository = new JdbcFareScheduleRepository(jdbc);
        // Ne pas inclure l'exception JDBC dans le bilan : elle pourrait citer l'URL locale.
        com.waka.pricing.domain.FareSchedule schedule;
        try {
            schedule = repository.findByVersion("launch-v1");
        } catch (Exception ignored) {
            fail("MYSQL_PRICING_READ_FAILED");
            return;
        }
        assertEquals(PricingOracle.schedule(), schedule);
        assertEquals(19, schedule.bands().size());
        var engine = new PricingEngine();
        for (var row : PricingOracle.rows("07_oracles_repartitions.csv")) {
            var band = schedule.bands().stream().filter(b -> b.id().equals(row[1])).findFirst().orElseThrow();
            var distance = BigDecimal.valueOf(band.lowerMetres());
            boolean payerEligible = Boolean.parseBoolean(row[4]);
            boolean driverEligible = Boolean.parseBoolean(row[5]);
            var result = "VIP".equals(row[2])
                    ? engine.vip(schedule, distance, payerEligible, driverEligible)
                    : engine.shared(schedule, distance, Integer.parseInt(row[3]), payerEligible, driverEligible);
            assertEquals(Long.parseLong(row[12]), result.priceXaf(), row[0]);
            assertEquals(Long.parseLong(row[13]), result.commissionXaf(), row[0]);
            assertEquals(Long.parseLong(row[14]), result.driverXaf(), row[0]);
            assertEquals(Long.parseLong(row[15]), result.payerReferrerXaf(), row[0]);
            assertEquals(Long.parseLong(row[16]), result.driverReferrerXaf(), row[0]);
            assertEquals(Long.parseLong(row[17]), result.wakaBeforeCostsXaf(), row[0]);
            PricingEngineTest.assertConservation(result);
        }
        var unknown = assertThrows(PricingException.class,
                () -> repository.findByVersion("missing-version"));
        assertEquals(PricingException.Code.UNKNOWN_FARE_VERSION, unknown.code());
        assertEquals(PricingException.Code.UNKNOWN_FARE_VERSION, assertThrows(PricingException.class,
                () -> repository.findByVersion("' OR '1'='1")).code());
        assertEquals(PricingException.Code.UNKNOWN_FARE_VERSION, assertThrows(PricingException.class,
                () -> repository.findByVersion("LAUNCH-V1")).code());
    }

    private static String required(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) { throw new IllegalStateException("MISSING_MYSQL_TEST_ENV"); }
        return value;
    }
}
