package com.waka.identity.profile;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import com.waka.identity.session.PersistentSessionService;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Dedicated memory-injected AES-256-GCM and HMAC-SHA-256 keys. */
public final class ProfileCrypto {
    private final byte[] encryptionKey;
    private final byte[] macKey;
    private final String keyId;
    private final SecureRandom random;

    public ProfileCrypto(byte[] encryptionKey, byte[] macKey, String keyId, SecureRandom random) {
        if (encryptionKey == null || encryptionKey.length != 32 || macKey == null || macKey.length != 32
                || MessageDigest.isEqual(encryptionKey, macKey) || keyId == null
                || !keyId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("INVALID_PROFILE_KEYS");
        this.encryptionKey = encryptionKey.clone();
        this.macKey = macKey.clone();
        this.keyId = keyId;
        this.random = Objects.requireNonNull(random);
    }

    public String keyId() { return keyId; }
    public boolean matchesEitherKey(byte[] key) {
        return key != null && (MessageDigest.isEqual(encryptionKey, key) || MessageDigest.isEqual(macKey, key));
    }
    public boolean reusesSessionKey(PersistentSessionService sessions) {
        return sessions.reusesSessionKey(encryptionKey) || sessions.reusesSessionKey(macKey);
    }

    public String requestMac(String account, String operation, String key) {
        return mac("profile-request-v1", account, operation, key);
    }
    public String payloadMac(String account, long expectedVersion, String normalizedName) {
        return mac("profile-payload-v1", account, Long.toString(expectedVersion),
                normalizedName == null ? "null" : "string", normalizedName == null ? "" : normalizedName);
    }
    public byte[] encrypt(byte[] plain, byte[] aad) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, aad).doFinal(plain);
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
    }
    public byte[] decrypt(byte[] ciphertext, byte[] aad) {
        if (ciphertext == null || ciphertext.length < 29) throw unavailable();
        try {
            byte[] nonce = Arrays.copyOf(ciphertext, 12);
            return cipher(Cipher.DECRYPT_MODE, nonce, aad).doFinal(ciphertext, 12, ciphertext.length - 12);
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
    }
    public static byte[] aad(String... fields) {
        byte[][] encoded = Arrays.stream(fields).map(x -> x.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
        int size = Arrays.stream(encoded).mapToInt(x -> 4 + x.length).sum();
        ByteBuffer result = ByteBuffer.allocate(size);
        for (byte[] field : encoded) result.putInt(field.length).put(field);
        return result.array();
    }
    private String mac(String... fields) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            mac.update(aad(fields));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
    }
    private Cipher cipher(int mode, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher;
    }
    private static ProfileException unavailable() { return new ProfileException(ProfileException.Code.SERVICE_UNAVAILABLE); }
    @Override public String toString() { return "ProfileCrypto[REDACTED]"; }
}
