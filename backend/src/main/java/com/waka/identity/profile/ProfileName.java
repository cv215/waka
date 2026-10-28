package com.waka.identity.profile;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** Optional display label, never a verified civil identity. */
public final class ProfileName {
    private ProfileName() { }

    public static String normalize(String input) {
        if (input == null) return null;
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
        int count = 0;
        for (int i = 0; i < normalized.length();) {
            int point = normalized.codePointAt(i);
            int type = Character.getType(point);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || point == 0x202A || point == 0x202B || point == 0x202C
                    || point == 0x202D || point == 0x202E) throw new ProfileException(ProfileException.Code.INVALID_REQUEST);
            i += Character.charCount(point);
        }
        int start = 0, end = normalized.length();
        while (start < end) {
            int point = normalized.codePointAt(start);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) break;
            start += Character.charCount(point);
        }
        while (end > start) {
            int point = normalized.codePointBefore(end);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) break;
            end -= Character.charCount(point);
        }
        String name = normalized.substring(start, end);
        count = name.codePointCount(0, name.length());
        if (count < 1 || count > 80 || name.getBytes(StandardCharsets.UTF_8).length > 320) {
            throw new ProfileException(ProfileException.Code.INVALID_REQUEST);
        }
        return name;
    }
}
