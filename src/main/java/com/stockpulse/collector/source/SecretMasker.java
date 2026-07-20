package com.stockpulse.collector.source;

/**
 * Redacts API keys from text before it reaches the logs.
 *
 * <p>ECOS puts the key in the URL path and OpenDART in a query parameter, so any exception whose
 * message echoes the request URI (WebClient does) would otherwise write the key in plaintext to
 * the log file. Sources mask their own failures rather than letting the raw exception escape.
 */
public final class SecretMasker {

    private static final String REDACTED = "***REDACTED***";

    private SecretMasker() {
    }

    /** Returns {@code text} with every occurrence of {@code secret} replaced by a placeholder. */
    public static String mask(String text, String secret) {
        if (text == null) {
            return null;
        }
        if (secret == null || secret.isBlank()) {
            return text;
        }
        return text.replace(secret, REDACTED);
    }
}
