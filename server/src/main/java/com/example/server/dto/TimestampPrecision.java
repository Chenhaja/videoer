package com.example.server.dto;

/** Server-owned precision of an evidence timestamp. */
public enum TimestampPrecision {
    MINUTE,
    SECOND;

    public static TimestampPrecision fromNullable(String value) {
        if (value == null || value.isBlank()) return MINUTE;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MINUTE;
        }
    }
}
