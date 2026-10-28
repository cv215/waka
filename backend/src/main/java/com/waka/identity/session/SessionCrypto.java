package com.waka.identity.session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stable identity lookup key is injected in memory, separate from the OTP keys. */
public final class SessionCrypto {
    private final byte[] identityLookupKey;
    private final SecureRandom random;
    public SessionCrypto(byte[] identityLookupKey, SecureRandom random) {
        if (identityLookupKey == null || identityLookupKey.length < 32 || identityLookupKey.length > 128) {
            throw new IllegalArgumentException("INVALID_SESSION_KEY");
        }
        this.identityLookupKey = identityLookupKey.clone();
        this.random = Objects.requireNonNull(random);
    }
    String gateHash() { return mac("session-gate-v1", ""); }
    String phoneHash(String normalizedPhone) { return mac("identity-phone-lookup-v1", normalizedPhone); }
    String contextHash(String client) { return mac("session-context-v1", client); }
    String recoveryRequestHash(String client, java.util.UUID key) {
        return mac("session-recovery-request-v1", client, key.toString());
    }
    String recoveryPayloadHash(java.util.UUID challenge, java.util.UUID proof, String context, String phone,
            String verificationBinding) {
        return mac("session-recovery-payload-v1", challenge.toString(), proof.toString(), context, phone, verificationBinding);
    }
    String revocationRequestHash(String client, java.util.UUID key) {
        return mac("session-revocation-request-v1", client, key.toString());
    }
    boolean matchesKey(byte[] other) { return MessageDigest.isEqual(identityLookupKey, other); }

    char[] newToken() {
        byte[] bytes = new byte[32];
        byte[] encoded = null;
        try {
            random.nextBytes(bytes);
            encoded = Base64.getUrlEncoder().withoutPadding().encode(bytes);
            char[] token = new char[encoded.length];
            for (int i = 0; i < token.length; i++) token[i] = (char) encoded[i];
            return token;
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    String tokenHash(char[] token) {
        if (token == null || token.length != 43) throw failure(SessionException.Code.INVALID_SESSION);
        byte[] ascii = new byte[token.length];
        byte[] bytes = null;
        byte[] canonical = null;
        try {
            for (int i = 0; i < token.length; i++) {
                char c = token[i];
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                    throw failure(SessionException.Code.INVALID_SESSION);
                }
                ascii[i] = (byte) c;
            }
            bytes = Base64.getUrlDecoder().decode(ascii);
            canonical = Base64.getUrlEncoder().withoutPadding().encode(bytes);
            if (bytes.length != 32 || !MessageDigest.isEqual(canonical, ascii)) {
                throw failure(SessionException.Code.INVALID_SESSION);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("waka-session-token-v1\0".getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw failure(SessionException.Code.INVALID_SESSION);
        } finally {
            Arrays.fill(ascii, (byte) 0);
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            if (canonical != null) Arrays.fill(canonical, (byte) 0);
        }
    }

    private String mac(String domain, String... values) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(identityLookupKey, "HmacSHA256"));
            byte[] label = domain.getBytes(StandardCharsets.US_ASCII);
            mac.update(ByteBuffer.allocate(4).putInt(label.length).array()); mac.update(label);
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                try { mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); mac.update(bytes); }
                finally { Arrays.fill(bytes, (byte) 0); }
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException unavailable) {
            throw failure(SessionException.Code.SERVICE_UNAVAILABLE);
        }
    }
    static boolean equal(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
    private static SessionException failure(SessionException.Code code) { return new SessionException(code); }
    @Override public String toString() { return "SessionCrypto[REDACTED]"; }
}

