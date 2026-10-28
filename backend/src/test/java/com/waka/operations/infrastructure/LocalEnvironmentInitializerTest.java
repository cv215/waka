package com.waka.operations.infrastructure;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests du garde en memoire uniquement : aucun serveur ni connexion JDBC. */
class LocalEnvironmentInitializerTest {

    private static final String LOCAL_URL = "jdbc:mysql://127.0.0.1:3306/waka_local"
            + "?sslMode=VERIFY_CA&connectTimeout=5000&socketTimeout=5000";

    @Test
    void acceptsExplicitDevelopmentConfiguration() {
        assertDoesNotThrow(() -> initialize(validEnvironment()));
    }

    @Test
    void acceptsTestOnlyWithItsSeparateDatabase() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles("test");
        environment.setProperty("spring.datasource.url", LOCAL_URL.replace("waka_local", "waka_test"));
        assertDoesNotThrow(() -> initialize(environment));
    }

    @Test
    void refusesMissingOrMultipleProfiles() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles();
        assertThrows(IllegalStateException.class, () -> initialize(environment));
        environment.setActiveProfiles("dev", "test");
        assertThrows(IllegalStateException.class, () -> initialize(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging", "default", "unknown"})
    void refusesUnpreparedProfiles(String profile) {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles(profile);
        assertThrows(IllegalStateException.class, () -> initialize(environment));
    }

    @ParameterizedTest
    @CsvSource({
            "server.address,0.0.0.0",
            "spring.datasource.username,root",
            "spring.datasource.hikari.connection-timeout,30000",
            "spring.datasource.hikari.validation-timeout,5000",
            "spring.datasource.hikari.maximum-pool-size,10",
            "spring.datasource.hikari.minimum-idle,0",
            "spring.jdbc.template.query-timeout,30s",
            "spring.main.lazy-initialization,true",
            "spring.flyway.enabled,false",
            "spring.flyway.clean-disabled,false",
            "spring.flyway.baseline-on-migrate,true",
            "spring.flyway.create-schemas,true",
            "spring.flyway.validate-on-migrate,false",
            "spring.flyway.skip-executing-migrations,true",
            "spring.flyway.locations,filesystem:other",
            "spring.sql.init.mode,always",
            "spring.jpa.hibernate.ddl-auto,update"
    })
    void refusesEffectiveHigherPriorityOverrides(String property, String value) {
        MockEnvironment environment = validEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("command-line-equivalent",
                Map.of(property, value)));
        assertThrows(IllegalStateException.class, () -> initialize(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.username", "spring.datasource.password", "spring.datasource.url"
    })
    void refusesEmptyCredentialsAndUrl(String property) {
        MockEnvironment environment = validEnvironment();
        environment.setProperty(property, " ");
        assertThrows(IllegalStateException.class, () -> initialize(environment));
    }

    @Test
    void refusesWrongDatabaseForEitherProfile() {
        MockEnvironment development = validEnvironment();
        development.setProperty("spring.datasource.url", LOCAL_URL.replace("waka_local", "waka_test"));
        assertThrows(IllegalStateException.class, () -> initialize(development));
        MockEnvironment test = validEnvironment();
        test.setActiveProfiles("test");
        assertThrows(IllegalStateException.class, () -> initialize(test));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://localhost:3306/waka_local?sslMode=VERIFY_CA&connectTimeout=5000&socketTimeout=5000",
            "jdbc:mysql://127.0.0.1,localhost/waka_local?sslMode=VERIFY_CA&connectTimeout=5000&socketTimeout=5000",
            "jdbc:mysql:loadbalance://127.0.0.1/waka_local?sslMode=VERIFY_CA&connectTimeout=5000&socketTimeout=5000",
            "jdbc:mysql://127.0.0.1/waka_local?sslMode=PREFERRED&connectTimeout=5000&socketTimeout=5000",
            "jdbc:mysql://127.0.0.1/waka_local?sslMode=VERIFY_CA&connectTimeout=0&socketTimeout=5000",
            "jdbc:mysql://127.0.0.1/waka_local?sslMode=VERIFY_CA&connectTimeout=5000&socketTimeout=0",
            "jdbc:mysql://127.0.0.1/waka_local?sslMode=VERIFY_CA&sslMode=DISABLED&connectTimeout=5000&socketTimeout=5000",
            "jdbc:mysql://127.0.0.1/waka_local?sslmode=VERIFY_CA&connectTimeout=5000&socketTimeout=5000",
            "jdbc:mysql://127.0.0.1/waka_local?sslMode=VERIFY_CA&connectTimeout=5000&socketTimeout=5000&user=root"
    })
    void refusesUnsafeOrAmbiguousJdbcUrls(String url) {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.datasource.url", url);
        assertThrows(IllegalStateException.class, () -> initialize(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.username",
            "spring.datasource.hikari.password", "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.catalog", "spring.datasource.hikari.schema",
            "spring.datasource.hikari.connection-init-sql",
            "spring.datasource.jndi-name", "spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
            "spring.flyway.schemas", "spring.flyway.default-schema", "spring.flyway.init-sqls",
            "spring.flyway.target",
            "spring.autoconfigure.exclude"
    })
    void refusesSeparateConnectionAndAutoconfigurationBypasses(String property) {
        MockEnvironment environment = validEnvironment();
        environment.setProperty(property, "UNIT_TEST_ONLY_UNSUPPORTED_OVERRIDE");
        assertThrows(IllegalStateException.class, () -> initialize(environment));
    }

    @Test
    void acceptsIdenticalButRefusesConflictingDriverProtection() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.datasource.hikari.data-source-properties.sslMode", "VERIFY_CA");
        assertDoesNotThrow(() -> initialize(environment));
        environment.setProperty("spring.datasource.hikari.data-source-properties.sslMode", "DISABLED");
        assertThrows(IllegalStateException.class, () -> initialize(environment));
    }

    @Test
    void neverIncludesRejectedPrivateValueOrBindingCauseInFailure() {
        MockEnvironment environment = validEnvironment();
        String marker = "UNIT_TEST_ONLY_PRIVATE_VALUE_MARKER";
        environment.setProperty("spring.datasource.hikari.connection-timeout", marker);
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> initialize(environment));
        assertFalse(exception.toString().contains(marker));
        assertNull(exception.getCause());
        assertEquals("INVALID_FORMAT", LocalEnvironmentInitializer.safeFailureCode(exception));
        assertEquals("INVALID_FORMAT", LocalEnvironmentInitializer.safeFailureCode(
                new IllegalStateException(marker, exception)));
        assertEquals("STARTUP_FAILED", LocalEnvironmentInitializer.safeFailureCode(
                new IllegalStateException(marker)));
    }

    private void initialize(MockEnvironment environment) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            new LocalEnvironmentInitializer().initialize(context);
        }
    }

    private MockEnvironment validEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.address", "127.0.0.1")
                .withProperty("spring.datasource.url", LOCAL_URL)
                .withProperty("spring.datasource.username", "UNIT_TEST_ONLY_USER")
                .withProperty("spring.datasource.password", "UNIT_TEST_ONLY_NO_REAL_SECRET")
                .withProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")
                .withProperty("spring.datasource.hikari.connection-timeout", "5000")
                .withProperty("spring.datasource.hikari.validation-timeout", "3000")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "3")
                .withProperty("spring.datasource.hikari.minimum-idle", "1")
                .withProperty("spring.jdbc.template.query-timeout", "3s")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.clean-disabled", "true")
                .withProperty("spring.flyway.baseline-on-migrate", "false")
                .withProperty("spring.flyway.create-schemas", "false")
                .withProperty("spring.flyway.validate-on-migrate", "true")
                .withProperty("spring.flyway.locations", "classpath:db/migration")
                .withProperty("spring.sql.init.mode", "never")
                .withProperty("spring.jpa.hibernate.ddl-auto", "none");
        environment.setActiveProfiles("dev");
        return environment;
    }
}
