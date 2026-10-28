package com.waka.identity.files;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrivateFileStoreTest {
    @TempDir Path root;
    private final String gate = "a".repeat(64);

    private PrivateFileStore store(String keyId) {
        byte[] enc = new byte[32], mac = new byte[32];
        java.util.Arrays.fill(enc, (byte) 1); java.util.Arrays.fill(mac, (byte) 2);
        return new PrivateFileStore(root, enc, mac, keyId, new SecureRandom());
    }

    @Test void contentIsEncryptedBoundToItsIdentifierAndTamperEvident() throws Exception {
        PrivateFileStore store = store("k1");
        UUID id = UUID.randomUUID();
        byte[] plain = "SYNTHETIC-PLATE-CONTENT".repeat(20).getBytes(StandardCharsets.US_ASCII);
        store.write(id, gate, plain);
        Path file = root.resolve(id.toString());
        byte[] onDisk = Files.readAllBytes(file);
        assertFalse(HexFormat.of().formatHex(onDisk).contains(HexFormat.of().formatHex("SYNTHETIC-PLATE".getBytes(StandardCharsets.US_ASCII))));
        assertArrayEquals(plain, store.read(id, gate));
        try (var names = Files.list(root)) { assertEquals(1, names.count(), "no temporary file left"); }

        UUID other = UUID.randomUUID();
        Files.copy(file, root.resolve(other.toString()));
        assertUnavailable(() -> store.read(other, gate));
        assertUnavailable(() -> store.read(id, "b".repeat(64)));
        assertUnavailable(() -> store("k2").read(id, gate));
        onDisk[onDisk.length - 1] ^= 1;
        Files.write(file, onDisk);
        assertUnavailable(() -> store.read(id, gate));
        store.deleteQuietly(id);
        assertFalse(Files.exists(file));
        assertUnavailable(() -> store.read(UUID.randomUUID(), gate));
    }

    @Test void requiresAnExistingAbsoluteDirectoryAndNeverReusesAName() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PrivateFileStore(Path.of("relative"), new byte[32], new byte[32], "k", new SecureRandom()));
        assertThrows(IllegalArgumentException.class, () -> new PrivateFileStore(root.resolve("missing"), new byte[32], new byte[32], "k", new SecureRandom()));
        PrivateFileStore store = store("k1");
        UUID id = UUID.randomUUID();
        store.write(id, gate, new byte[] { 1 });
        assertUnavailable(() -> store.write(id, gate, new byte[] { 2 }));
        assertArrayEquals(new byte[] { 1 }, store.read(id, gate));
        assertEquals("PrivateFileStore[REDACTED]", store.toString());
    }

    private static void assertUnavailable(Runnable operation) {
        assertEquals(FileException.Code.SERVICE_UNAVAILABLE, assertThrows(FileException.class, operation::run).code());
    }
}
