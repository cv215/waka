package com.waka.identity.privacy;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** Optional free text of a privacy request: NFC, 1 to 500 code points, line breaks allowed, no hidden controls. */
public final class PrivacyDetails {
    public static final int MAX_CODE_POINTS = 500;
    private PrivacyDetails() { }

    public static String normalize(String input) {
        if (input == null) return null;
        String text = Normalizer.normalize(input, Normalizer.Form.NFC).strip();
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            int type = Character.getType(point);
            boolean lineBreak = point == '\n';
            if (!lineBreak && (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR)) {
                throw new PrivacyException(PrivacyException.Code.INVALID_REQUEST);
            }
            i += Character.charCount(point);
        }
        int count = text.codePointCount(0, text.length());
        if (count < 1 || count > MAX_CODE_POINTS || text.getBytes(StandardCharsets.UTF_8).length > 2000) {
            throw new PrivacyException(PrivacyException.Code.INVALID_REQUEST);
        }
        return text;
    }
}
