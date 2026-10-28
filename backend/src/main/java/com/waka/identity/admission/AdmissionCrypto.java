package com.waka.identity.admission;

import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.profile.ProfileException;
import com.waka.identity.session.PersistentSessionService;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Dedicated memory-injected keys for candidature data. AES-GCM reuses the reviewed profile
 * primitive; commands are bound by a domain-separated HMAC-SHA-256 over length-prefixed fields.
 */
public final class AdmissionCrypto {
    private final ProfileCrypto cipher;
    private final byte[] macKey;

    public AdmissionCrypto(byte[] encryptionKey, byte[] macKey, String keyId, SecureRandom random) {
        cipher = new ProfileCrypto(encryptionKey, macKey, keyId, random);
        this.macKey = macKey.clone();
    }

    public String keyId() { return cipher.keyId(); }
    boolean reusesSessionKey(PersistentSessionService sessions) { return cipher.reusesSessionKey(sessions); }
    /** True when one of these keys is also used elsewhere (for example by the profile). */
    public boolean matchesEitherKey(byte[] key) { return cipher.matchesEitherKey(key); }

    String mac(String domain, String... fields) {
        String[] all = new String[fields.length + 1];
        all[0] = domain; System.arraycopy(fields, 0, all, 1, fields.length);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ProfileCrypto.aad(all)));
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
    }
    byte[] encrypt(byte[] plain, byte[] aad) { return wrap(() -> cipher.encrypt(plain, aad)); }
    byte[] decrypt(byte[] ciphertext, byte[] aad) { return wrap(() -> cipher.decrypt(ciphertext, aad)); }
    static byte[] aad(String... fields) { return ProfileCrypto.aad(fields); }
    static boolean equal(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] wrap(Supplier<byte[]> operation) {
        try { return operation.get(); }
        catch (ProfileException hidden) { throw unavailable(); }
    }
    private static AdmissionException unavailable() { return new AdmissionException(AdmissionException.Code.SERVICE_UNAVAILABLE); }

    @Override public String toString() { return "AdmissionCrypto[REDACTED]"; }
}
