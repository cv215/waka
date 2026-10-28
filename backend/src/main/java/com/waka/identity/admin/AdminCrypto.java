package com.waka.identity.admin;

import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.profile.ProfileException;
import com.waka.identity.session.PersistentSessionService;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Dedicated memory-injected keys: AES-GCM protects TOTP secrets at rest, HMAC-SHA-256 stores
 * only an imprint of each administrative token. Keys must differ from session keys.
 */
public final class AdminCrypto {
    private final ProfileCrypto cipher;
    private final byte[] macKey;
    private final SecureRandom random;

    public AdminCrypto(byte[] encryptionKey, byte[] macKey, String keyId, SecureRandom random) {
        cipher = new ProfileCrypto(encryptionKey, macKey, keyId, random);
        this.macKey = macKey.clone();
        this.random = random;
    }

    public String keyId() { return cipher.keyId(); }
    boolean reusesSessionKey(PersistentSessionService sessions) { return cipher.reusesSessionKey(sessions); }

    /** 43-character base64url token, the same shape as a client bearer token. */
    char[] newToken() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).toCharArray(); }
        finally { Arrays.fill(raw, (byte) 0); }
    }
    byte[] newSecret() { byte[] secret = new byte[20]; random.nextBytes(secret); return secret; }

    String tokenHash(char[] token) {
        var bytes = StandardCharsets.US_ASCII.encode(CharBuffer.wrap(token));
        byte[] plain = new byte[bytes.remaining()];
        bytes.get(plain);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            mac.update("waka-admin-token-v1".getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(plain));
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    static boolean equal(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    byte[] encrypt(byte[] plain, byte[] aad) { return wrap(() -> cipher.encrypt(plain, aad)); }
    byte[] decrypt(byte[] ciphertext, byte[] aad) { return wrap(() -> cipher.decrypt(ciphertext, aad)); }
    static byte[] aad(String... fields) { return ProfileCrypto.aad(fields); }

    private static byte[] wrap(Supplier<byte[]> operation) {
        try { return operation.get(); }
        catch (ProfileException hidden) { throw unavailable(); }
    }
    private static AdminException unavailable() { return new AdminException(AdminException.Code.SERVICE_UNAVAILABLE); }

    @Override public String toString() { return "AdminCrypto[REDACTED]"; }
}
