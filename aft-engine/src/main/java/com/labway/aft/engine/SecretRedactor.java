package com.labway.aft.engine;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class SecretRedactor {
    private static final Set<String> SENSITIVE = Set.of(
            "authorization", "cookie", "set-cookie", "password", "token", "secret", "api-key", "x-api-key"
    );

    public Map<String, String> redactHeaders(Map<String, String> headers) {
        Map<String, String> redacted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((key, value) -> redacted.put(key, isSensitive(key) ? "***" : value));
        return redacted;
    }

    public String redactText(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll(
                        "(?i)(\"?(?:password|token|secret|authorization)\"?\\s*[:=]\\s*\"?)[^\",&\\s}]+",
                        "$1***"
                )
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+", "$1***");
    }

    private boolean isSensitive(String key) {
        String normalized = key.toLowerCase();
        return SENSITIVE.stream().anyMatch(normalized::contains);
    }
}
