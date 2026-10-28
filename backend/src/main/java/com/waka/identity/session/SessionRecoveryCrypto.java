package com.waka.identity.session;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Dedicated AES-256 key supplied in memory; no loading, fallback or automatic rotation. */
public final class SessionRecoveryCrypto {
    private final byte[] key;
    private final String keyId;
    private final SecureRandom random;
    public SessionRecoveryCrypto(byte[] key, String keyId, SecureRandom random) {
        if (key == null || key.length != 32 || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("INVALID_SESSION_RECOVERY_KEY");
        }
        this.key = key.clone(); this.keyId = keyId; this.random = Objects.requireNonNull(random);
    }
    String keyId() { return keyId; }
    boolean reusesIdentityKey(SessionCrypto identity) { return identity.matchesKey(key); }
    boolean matchesKey(byte[] candidate) { return candidate != null && java.security.MessageDigest.isEqual(key, candidate); }

    byte[] encrypt(char[] token, byte[] aad) {
        if (token == null || token.length != 43) throw unavailable();
        byte[] plain = new byte[token.length];
        try {
            for (int i = 0; i < token.length; i++) {
                if (token[i] > 127) throw unavailable();
                plain[i] = (byte) token[i];
            }
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, aad).doFinal(plain);
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    char[] decrypt(byte[] ciphertext, byte[] aad) {
        if (ciphertext == null || ciphertext.length != 71) throw unavailable();
        byte[] plain = null;
        try {
            byte[] nonce = Arrays.copyOf(ciphertext, 12);
            plain = cipher(Cipher.DECRYPT_MODE, nonce, aad).doFinal(ciphertext, 12, ciphertext.length - 12);
            if (plain.length != 43) throw unavailable();
            char[] token = new char[plain.length];
            for (int i = 0; i < plain.length; i++) token[i] = (char) (plain[i] & 0xff);
            return token;
        } catch (GeneralSecurityException hidden) { throw unavailable(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private Cipher cipher(int mode, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad); return cipher;
    }
    private static SessionException unavailable() { return new SessionException(SessionException.Code.SERVICE_UNAVAILABLE); }
    @Override public String toString() { return "SessionRecoveryCrypto[REDACTED]"; }
}

