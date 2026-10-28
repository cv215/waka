package com.waka.identity.sms.orange;

import com.waka.identity.sms.orange.PersonalSmsCheck.State;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** Private one-process protocol for the local Windows confirmation UI. */
public final class PersonalSmsCheckMain {
    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_LINE = 2048;
    private static final String RETRY_02 = "--authorized-retry-02";
    private static final String RECIPIENT_03 = "--authorized-recipient-03";
    private PersonalSmsCheckMain() { }

    public static void main(String[] args) {
        run(System.in, System.out, args);
    }

    static void run(InputStream input, OutputStream output) {
        run(input, output, new String[0]);
    }

    static void run(InputStream input, OutputStream output, String[] args) {
        // Only the two fixed additional reservations are authorized; no path, endpoint or sender argument.
        if (args == null || (args.length != 0
                && (args.length != 1 || (!RETRY_02.equals(args[0]) && !RECIPIENT_03.equals(args[0]))))) {
            emit(output, State.INVALID_INPUT);
            return;
        }
        boolean retry02 = args.length == 1 && RETRY_02.equals(args[0]);
        boolean recipient03 = args.length == 1 && RECIPIENT_03.equals(args[0]);
        PersonalSmsCheck check = null;
        char[] clientId = null;
        char[] clientSecret = null;
        char[] phone = null;
        try {
            byte[] initial = readLine(input);
            if (initial == null) { return; }
            Map<String, Object> init;
            try { init = parse(initial); }
            catch (Exception invalid) { emit(output, State.INVALID_INPUT); return; }
            finally { Arrays.fill(initial, (byte) 0); }
            if (!init.keySet().equals(Set.of("clientId", "clientSecret", "phoneNumber"))
                    || !(init.get("clientId") instanceof String id)
                    || !(init.get("clientSecret") instanceof String secret)
                    || !(init.get("phoneNumber") instanceof String number)) {
                emit(output, State.INVALID_INPUT);
                return;
            }
            clientId = id.toCharArray();
            clientSecret = secret.toCharArray();
            phone = number.toCharArray();
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null || localAppData.isBlank()) { emit(output, State.ERROR); return; }
            Path gateDirectory = Path.of(localAppData, "WAKA", "Secrets", "sms-test");
            PersonalSmsCheck.Gate gate = recipient03 ? recipient03Gate(gateDirectory)
                    : retry02 ? retryGate(gateDirectory)
                    : new PersonalSmsCheck.FileGate(gateDirectory.resolve("personal-check.lock"));
            OrangeSmsGateway gateway = OrangeSmsGateway.usingDefaultSender(clientId, clientSecret);
            try {
                check = new PersonalSmsCheck(gateway, phone, Clock.systemUTC(), System::nanoTime,
                        gate, new SecureRandom());
            } catch (Exception failure) {
                gateway.close();
                throw failure;
            }
            Arrays.fill(clientId, '\0'); clientId = null;
            Arrays.fill(clientSecret, '\0'); clientSecret = null;
            Arrays.fill(phone, '\0'); phone = null;
            emit(output, State.READY);

            while (true) {
                byte[] line = readLine(input);
                if (line == null) { return; }
                Map<String, Object> command;
                try { command = parse(line); }
                catch (Exception invalid) { emit(output, State.INVALID_INPUT); return; }
                finally { Arrays.fill(line, (byte) 0); }
                if (!(command.get("action") instanceof String action)) {
                    emit(output, State.INVALID_INPUT); return;
                }
                if ("exit".equals(action) && command.keySet().equals(Set.of("action"))) {
                    emit(output, State.CLOSED); return;
                }
                if ("send".equals(action) && command.keySet().equals(Set.of("action", "consent"))
                        && command.get("consent") instanceof Boolean consent) {
                    emit(output, check.send(consent));
                    continue;
                }
                if ("verify".equals(action) && command.keySet().equals(Set.of("action", "code"))) {
                    Object value = command.get("code");
                    char[] candidate = value instanceof String raw ? raw.toCharArray() : null;
                    try { emit(output, check.verify(candidate)); }
                    finally { if (candidate != null) { Arrays.fill(candidate, '\0'); } }
                    continue;
                }
                emit(output, State.INVALID_INPUT);
                return;
            }
        } catch (Exception failure) {
            emit(output, State.ERROR);
        } finally {
            if (clientId != null) { Arrays.fill(clientId, '\0'); }
            if (clientSecret != null) { Arrays.fill(clientSecret, '\0'); }
            if (phone != null) { Arrays.fill(phone, '\0'); }
            if (check != null) { check.close(); }
        }
    }

    /** The second and final reservation requires the original regular file without touching it. */
    static PersonalSmsCheck.Gate retryGate(Path directory) {
        Path original = directory.resolve("personal-check.lock");
        PersonalSmsCheck.FileGate second = new PersonalSmsCheck.FileGate(
                directory.resolve("personal-check-retry-02.lock"));
        return () -> {
            if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("ORIGINAL_GATE_MISSING");
            }
            return second.reserve();
        };
    }

    /** A single third recipient check needs both earlier reservations, without changing either. */
    static PersonalSmsCheck.Gate recipient03Gate(Path directory) {
        Path original = directory.resolve("personal-check.lock");
        Path retry02 = directory.resolve("personal-check-retry-02.lock");
        PersonalSmsCheck.FileGate third = new PersonalSmsCheck.FileGate(
                directory.resolve("personal-check-recipient-03.lock"));
        return () -> {
            if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(retry02, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("EARLIER_GATE_MISSING");
            }
            return third.reserve();
        };
    }

    private static byte[] readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') { return line.toByteArray(); }
            if (line.size() >= MAX_LINE) { throw new IOException("LINE_LIMIT"); }
            line.write(next);
        }
        return line.size() == 0 ? null : line.toByteArray();
    }

    private static Map<String, Object> parse(byte[] line) throws IOException {
        if (line.length == 0) { throw new IOException("INVALID_JSON"); }
        Map<String, Object> fields = new HashMap<>();
        try (JsonParser parser = JSON.createParser(line)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) { throw new IOException("INVALID_JSON"); }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.getString();
                if (fields.containsKey(name)) { throw new IOException("INVALID_JSON"); }
                JsonToken kind = parser.nextToken();
                if (kind == JsonToken.VALUE_STRING) { fields.put(name, parser.getString()); }
                else if (kind == JsonToken.VALUE_TRUE) { fields.put(name, Boolean.TRUE); }
                else if (kind == JsonToken.VALUE_FALSE) { fields.put(name, Boolean.FALSE); }
                else { throw new IOException("INVALID_JSON"); }
            }
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                throw new IOException("INVALID_JSON");
            }
        }
        return fields;
    }

    private static void emit(OutputStream output, State state) {
        try {
            output.write(("{\"state\":\"" + state.name() + "\"}\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (IOException ignored) { /* Broken local pipe; close on return. */ }
    }
}
