package com.example.server.dto;

import java.util.List;

/** 用户可直接跳转和核验的视频证据。 */
public record VideoEvidenceHit(
        long startMs,
        long endMs,
        String source,
        String snippet,
        String transcript,
        List<String> ocrTexts,
        TimestampPrecision timestampPrecision
) {
    public VideoEvidenceHit(long startMs,
                            long endMs,
                            String source,
                            String snippet,
                            String transcript,
                            List<String> ocrTexts) {
        this(startMs, endMs, source, snippet, transcript, ocrTexts, TimestampPrecision.MINUTE);
    }

    public VideoEvidenceHit {
        source = source == null ? "" : source;
        snippet = snippet == null ? "" : snippet;
        transcript = transcript == null ? "" : transcript;
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        timestampPrecision = timestampPrecision == null ? TimestampPrecision.MINUTE : timestampPrecision;
    }
}
