package com.waka.operations.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeLoggingConfigurationTest {

    @Test
    void defaultConsoleSuppressesDependenciesStackTracesAndFileLogs() throws Exception {
        String marker = "UNIT_TEST_ONLY_PRIVATE_LOG_MARKER";
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        try (PrintStream console = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(console);
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
            assertEquals(Level.OFF, context.getLogger("ROOT").getLevel());
            assertFalse(context.getLogger("ROOT").iteratorForAppenders().hasNext());
            var operational = context.getLogger("com.waka.operational");
            assertFalse(operational.isAdditive());
            var appenders = operational.iteratorForAppenders();
            assertTrue(appenders.hasNext());
            assertEquals("ch.qos.logback.core.ConsoleAppender", appenders.next().getClass().getName());
            assertFalse(appenders.hasNext());

            context.getLogger("org.flywaydb").info(marker);
            context.getLogger("com.zaxxer.hikari").error(marker, new IllegalStateException(marker));
            context.getLogger("org.springframework.boot").error(marker, new IllegalStateException(marker));
            operational.info("APPLICATION_READY");
            console.flush();
            assertEquals("APPLICATION_READY" + System.lineSeparator(), bytes.toString(StandardCharsets.UTF_8));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(marker));
        } finally {
            System.setOut(original);
            context.stop();
        }
    }
}
