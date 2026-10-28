package com.waka.operations.infrastructure;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/** Verifie la configuration effective avant creation des connexions et migrations. */
public final class LocalEnvironmentInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        try {
            validate(context.getEnvironment());
        } catch (RejectedConfiguration exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Les exceptions de conversion peuvent contenir la valeur privee rejetee.
            throw rejected("INVALID_FORMAT");
        }
    }

    private void validate(ConfigurableEnvironment environment) {
        String[] profiles = environment.getActiveProfiles();
        require(profiles.length == 1, "EXPLICIT_SINGLE_PROFILE_REQUIRED");
        String database = switch (profiles[0]) {
            case "dev" -> "waka_local";
            case "test" -> "waka_test";
            default -> throw rejected("PROFILE_NOT_ENABLED");
        };

        Binder binder = Binder.get(environment);
        require("127.0.0.1".equals(text(binder, "server.address")), "LOCAL_HTTP_REQUIRED");
        require(!bool(binder, "spring.main.lazy-initialization", false), "LAZY_START_NOT_ALLOWED");
        require(binder.bind("spring.autoconfigure.exclude", Bindable.listOf(String.class))
                .orElse(List.of()).isEmpty(), "AUTOCONFIG_EXCLUSION_NOT_ALLOWED");

        String user = text(binder, "spring.datasource.username");
        require(!"root".equalsIgnoreCase(user.strip()), "ADMIN_DATABASE_USER_NOT_ALLOWED");
        text(binder, "spring.datasource.password");
        require("com.mysql.cj.jdbc.Driver".equals(text(binder, "spring.datasource.driver-class-name")),
                "MYSQL_DRIVER_REQUIRED");

        // Ces proprietes ouvriraient une seconde voie de connexion hors du contrat local.
        for (String property : List.of("spring.datasource.hikari.jdbc-url",
                "spring.datasource.hikari.username", "spring.datasource.hikari.password",
                "spring.datasource.hikari.data-source-class-name", "spring.datasource.jndi-name",
                "spring.datasource.hikari.catalog", "spring.datasource.hikari.schema",
                "spring.datasource.hikari.connection-init-sql",
                "spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
                "spring.flyway.schemas", "spring.flyway.default-schema", "spring.flyway.init-sqls",
                "spring.flyway.target")) {
            require(!binder.bind(property, String.class).isBound(), "ALTERNATE_CONNECTION_NOT_ALLOWED");
        }
        String type = binder.bind("spring.datasource.type", String.class).orElse(null);
        require(type == null || "com.zaxxer.hikari.HikariDataSource".equals(type), "POOL_TYPE_NOT_ALLOWED");

        Map<String, String> query = query(text(binder, "spring.datasource.url"), database);
        Map<String, String> driver = binder.bind("spring.datasource.hikari.data-source-properties",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        rejectConnectionOverrides(query);
        rejectConnectionOverrides(driver);
        driverValue(query, driver, "sslMode", "VERIFY_CA");
        driverValue(query, driver, "connectTimeout", "5000");
        driverValue(query, driver, "socketTimeout", "5000");

        require(number(binder, "spring.datasource.hikari.connection-timeout") == 5000,
                "POOL_CONNECTION_TIMEOUT_REQUIRED");
        require(number(binder, "spring.datasource.hikari.validation-timeout") == 3000,
                "POOL_VALIDATION_TIMEOUT_REQUIRED");
        require(number(binder, "spring.datasource.hikari.maximum-pool-size") == 3,
                "LOCAL_POOL_SIZE_REQUIRED");
        require(number(binder, "spring.datasource.hikari.minimum-idle") == 1,
                "LOCAL_POOL_IDLE_REQUIRED");
        require(Duration.ofSeconds(3).equals(binder.bind("spring.jdbc.template.query-timeout",
                Duration.class).orElse(null)), "QUERY_TIMEOUT_REQUIRED");

        require(bool(binder, "spring.flyway.enabled", false), "MIGRATION_REQUIRED");
        require(bool(binder, "spring.flyway.clean-disabled", false), "CLEAN_MUST_BE_DISABLED");
        require(!bool(binder, "spring.flyway.baseline-on-migrate", true), "BASELINE_NOT_ALLOWED");
        require(!bool(binder, "spring.flyway.create-schemas", true), "SCHEMA_CREATION_NOT_ALLOWED");
        require(bool(binder, "spring.flyway.validate-on-migrate", false), "MIGRATION_VALIDATION_REQUIRED");
        require(!bool(binder, "spring.flyway.skip-executing-migrations", false), "MIGRATION_EXECUTION_REQUIRED");
        require(binder.bind("spring.flyway.locations", Bindable.listOf(String.class))
                .orElse(List.of()).equals(List.of("classpath:db/migration")), "MIGRATION_LOCATION_NOT_ALLOWED");
        require("never".equalsIgnoreCase(text(binder, "spring.sql.init.mode")), "SQL_INIT_NOT_ALLOWED");
        require("none".equalsIgnoreCase(text(binder, "spring.jpa.hibernate.ddl-auto")),
                "AUTOMATIC_DDL_NOT_ALLOWED");
    }

    private Map<String, String> query(String jdbcUrl, String database) {
        require(jdbcUrl.startsWith("jdbc:mysql://"), "SINGLE_MYSQL_URL_REQUIRED");
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        require("127.0.0.1".equals(uri.getHost()) && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null && uri.getPort() >= -1 && uri.getPort() != 0
                && uri.getPort() <= 65535, "LOCAL_DATABASE_HOST_REQUIRED");
        require(("/" + database).equals(uri.getRawPath()), "DATABASE_PROFILE_MISMATCH");
        Map<String, String> parameters = new HashMap<>();
        Set<String> seen = new HashSet<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            for (String entry : rawQuery.split("&", -1)) {
                String[] pair = entry.split("=", 2);
                require(pair.length == 2 && !pair[0].isEmpty(), "INVALID_DRIVER_PARAMETER");
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                require(seen.add(key.toLowerCase(Locale.ROOT)), "DUPLICATE_DRIVER_PARAMETER");
                parameters.put(key, value);
            }
        }
        return parameters;
    }

    private void rejectConnectionOverrides(Map<String, String> properties) {
        Set<String> forbidden = Set.of("user", "username", "password", "url", "jdbcurl", "host",
                "servername", "port", "portnumber", "databasename", "socketfactory", "dnssrv");
        for (String key : properties.keySet()) {
            require(!forbidden.contains(key.toLowerCase(Locale.ROOT)), "DRIVER_CONNECTION_OVERRIDE_NOT_ALLOWED");
        }
    }

    private void driverValue(Map<String, String> query, Map<String, String> driver,
            String key, String expected) {
        String queryValue = canonicalValue(query, key);
        String driverValue = canonicalValue(driver, key);
        require(queryValue == null || driverValue == null || queryValue.equals(driverValue),
                "CONFLICTING_DRIVER_PARAMETER");
        require(expected.equals(queryValue != null ? queryValue : driverValue),
                "REQUIRED_DRIVER_PROTECTION_MISSING");
    }

    private String canonicalValue(Map<String, String> properties, String key) {
        for (String candidate : properties.keySet()) {
            require(!candidate.equalsIgnoreCase(key) || candidate.equals(key), "INVALID_DRIVER_PARAMETER_NAME");
        }
        return properties.get(key);
    }

    private String text(Binder binder, String property) {
        String value = binder.bind(property, String.class).orElse(null);
        require(value != null && !value.isBlank() && !value.contains("${"), "REQUIRED_PROPERTY_MISSING");
        return value;
    }

    private boolean bool(Binder binder, String property, boolean missing) {
        return binder.bind(property, Boolean.class).orElse(missing);
    }

    private long number(Binder binder, String property) {
        return binder.bind(property, Long.class).orElse(-1L);
    }

    private void require(boolean condition, String code) {
        if (!condition) {
            throw rejected(code);
        }
    }

    private RejectedConfiguration rejected(String code) {
        return new RejectedConfiguration(code);
    }

    /** Retourne seulement le code interne du garde ; jamais un message de dependance. */
    public static String safeFailureCode(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++, current = current.getCause()) {
            if (current instanceof RejectedConfiguration rejected) {
                return rejected.code;
            }
        }
        return "STARTUP_FAILED";
    }

    private static final class RejectedConfiguration extends IllegalStateException {
        private final String code;

        private RejectedConfiguration(String code) {
            super("WAKA configuration rejected: " + code);
            this.code = code;
        }
    }
}
