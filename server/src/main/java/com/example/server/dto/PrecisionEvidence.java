package com.example.server.dto;

/** A real timestamp emitted by an ASR sentence or OCR frame. */
public record PrecisionEvidence(
        long startMs,
        long endMs,
        String source,
        String text,
        String frameUrl
) {
    public PrecisionEvidence {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("invalid precision evidence range");
        }
        source = source == null ? "UNKNOWN" : source.trim();
        text = text == null ? "" : text.trim();
        frameUrl = frameUrl == null ? "" : frameUrl.trim();
    }

    public static PrecisionEvidence asr(long startMs, long endMs, String text) {
        return new PrecisionEvidence(startMs, endMs, "ASR", text, "");
    }

    public static PrecisionEvidence ocr(long timestampMs, String text, String frameUrl) {
        return new PrecisionEvidence(timestampMs, timestampMs + 1, "OCR", text, frameUrl);
    }
}
