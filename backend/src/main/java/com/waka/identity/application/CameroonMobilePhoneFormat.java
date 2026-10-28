package com.waka.identity.application;

/** Controle syntaxique pur ; ne prouve jamais attribution, disponibilite ou possession. */
public final class CameroonMobilePhoneFormat {
    public static final int MAX_INPUT_LENGTH = 40;

    private CameroonMobilePhoneFormat() { }

    /** Retourne la representation E.164 uniquement en memoire, sans aucun effet externe. */
    public static String normalize(String input) {
        if (input == null || input.isEmpty() || input.length() > MAX_INPUT_LENGTH) {
            throw invalid();
        }
        StringBuilder compact = new StringBuilder(MAX_INPUT_LENGTH);
        for (int i = 0; i < input.length(); i++) {
            char value = input.charAt(i);
            if (value == ' ' || value == '\u00a0' || value == '\u202f'
                    || value == '-' || value == '(' || value == ')') {
                continue;
            }
            if ((value >= '0' && value <= '9') || value == '+') {
                compact.append(value);
            } else {
                throw invalid();
            }
        }
        String national = compact.toString();
        if (national.startsWith("+237")) {
            national = national.substring(4);
        } else if (national.startsWith("00237")) {
            national = national.substring(5);
        }
        if (!national.matches("6[0-9]{8}")) {
            throw invalid();
        }
        return "+237" + national;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_PHONE_FORMAT");
    }
}
