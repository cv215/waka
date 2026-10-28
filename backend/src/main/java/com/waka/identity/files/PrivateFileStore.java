package com.waka.identity.files;

import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.profile.ProfileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Private files on a local directory outside any public root. Names are random identifiers,
 * content is AES-GCM encrypted with a dedicated key and bound to its identifier; nothing here
 * is ever served by URL. The directory is injected and must already exist.
 */
public final class PrivateFileStore {
    private final Path root;
    private final ProfileCrypto cipher;
    private final byte[] macKey;

    public PrivateFileStore(Path root, byte[] encryptionKey, byte[] macKey, String keyId, SecureRandom random) {
        Objects.requireNonNull(root);
        if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("PRIVATE_STORAGE_DIRECTORY_REQUIRED");
        }
        this.root = root.normalize();
        this.cipher = new ProfileCrypto(encryptionKey, macKey, keyId, random);
        this.macKey = macKey.clone();
    }

    public String keyId() { return cipher.keyId(); }
    public boolean matchesEitherKey(byte[] key) { return cipher.matchesEitherKey(key); }
    boolean reusesSessionKey(com.waka.identity.session.PersistentSessionService sessions) { return cipher.reusesSessionKey(sessions); }

    /** HMAC over the received bytes: binds an idempotent command without storing a plain digest. */
    String contentMac(String domain, byte[] content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            mac.update(ProfileCrypto.aad(domain));
            return HexFormat.of().formatHex(mac.doFinal(content));
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
    }
    String mac(String... fields) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ProfileCrypto.aad(fields)));
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
    }

    /** Writes atomically (temporary file then move) and returns only after the content is on disk. */
    void write(UUID id, String gate, byte[] plain) {
        byte[] encrypted;
        try { encrypted = cipher.encrypt(plain, aad(id, gate)); }
        catch (ProfileException hidden) { throw unavailable(); }
        Path target = path(id), temporary = root.resolve(id + ".tmp");
        try {
            // On Windows an atomic move replaces an existing target: a name is never written twice.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            Files.write(temporary, encrypted, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException hidden) {
            deleteQuietly(temporary);
            throw unavailable();
        } finally { java.util.Arrays.fill(encrypted, (byte) 0); }
    }

    byte[] read(UUID id, String gate) {
        byte[] encrypted;
        try { encrypted = Files.readAllBytes(path(id)); }
        catch (IOException hidden) { throw unavailable(); }
        try { return cipher.decrypt(encrypted, aad(id, gate)); }
        catch (ProfileException hidden) { throw unavailable(); }
    }

    /** Used when the database refused the record: an unreferenced encrypted file must not remain. */
    void deleteQuietly(UUID id) { deleteQuietly(path(id)); }

    private Path path(UUID id) {
        Path resolved = root.resolve(id.toString()).normalize();
        if (!resolved.getParent().equals(root)) throw unavailable();
        return resolved;
    }
    private byte[] aad(UUID id, String gate) { return ProfileCrypto.aad("waka-private-file-v1", gate, id.toString(), cipher.keyId()); }
    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { /* reported by a later maintenance sweep */ }
    }
    private static FileException unavailable() { return new FileException(FileException.Code.SERVICE_UNAVAILABLE); }
    @Override public String toString() { return "PrivateFileStore[REDACTED]"; }
}
