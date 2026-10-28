package com.waka.identity.otp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Keys are provided in memory. This class neither creates nor stores configuration keys. */
public final class OtpCrypto {
    private final byte[] macKey;
    private final byte[] encryptionKey;
    private final SecureRandom random;

    public OtpCrypto(byte[] macKey, byte[] encryptionKey, SecureRandom random) {
        if (macKey == null || macKey.length < 32 || macKey.length > 128
                || encryptionKey == null || encryptionKey.length != 32
                || MessageDigest.isEqual(macKey, encryptionKey)) {
            throw new IllegalArgumentException("INVALID_OTP_KEYS");
        }
        this.macKey = macKey.clone(); this.encryptionKey = encryptionKey.clone();
        this.random = Objects.requireNonNull(random);
    }

    public char[] newCode() {
        int number = random.nextInt(1_000_000);
        char[] result = new char[6];
        for (int i = 5; i >= 0; i--) { result[i] = (char) ('0' + number % 10); number /= 10; }
        return result;
    }

    /** Length-prefix every element and domain separate every use. */
    String fingerprint(String domain, String... values) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            update(mac, domain);
            for (String value : values) update(mac, value);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException impossible) { throw unavailable(); }
    }

    private static void update(Mac mac, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try { mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); mac.update(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    String codeFingerprint(UUID id, String contextHash, char[] code) {
        byte[] bytes = new byte[code.length];
        try {
            for (int i = 0; i < code.length; i++) bytes[i] = (byte) code[i];
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            update(mac, "otp-code-v1"); update(mac, id.toString()); update(mac, contextHash);
            mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); mac.update(bytes);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException failure) { throw unavailable(); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    public byte[] encryptDestination(UUID id, char[] destination) {
        if (destination == null || destination.length > 40) throw unavailable();
        byte[] plain = new byte[destination.length];
        try {
            for (int i = 0; i < destination.length; i++) {
                if (destination[i] > 127) throw unavailable();
                plain[i] = (byte) destination[i];
            }
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, id, nonce);
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException failure) { throw unavailable(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public char[] decryptDestination(UUID id, byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length < 29 || ciphertext.length > 128) throw unavailable();
        byte[] plain = null;
        try {
            byte[] nonce = Arrays.copyOf(ciphertext, 12);
            plain = cipher(Cipher.DECRYPT_MODE, id, nonce).doFinal(ciphertext, 12, ciphertext.length - 12);
            char[] result = new char[plain.length];
            for (int i = 0; i < plain.length; i++) result[i] = (char) (plain[i] & 0xff);
            return result;
        } catch (GeneralSecurityException failure) { throw unavailable(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private Cipher cipher(int mode, UUID id, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(id.toString().getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }

    static boolean equal(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
    private static OtpException unavailable() { return new OtpException(OtpException.Code.SERVICE_UNAVAILABLE); }
    @Override public String toString() { return "OtpCrypto[REDACTED]"; }
}
