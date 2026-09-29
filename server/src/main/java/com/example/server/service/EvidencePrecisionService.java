package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.PrecisionEvidence;
import com.example.server.dto.PrecisionEvidenceIndex;
import com.example.server.dto.TimestampPrecision;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Resolves an Agent window anchor against server-owned ASR/OCR timestamps. */
@Service
public class EvidencePrecisionService {

    public AnalysisResult enrich(VideoContext context,
                                 PrecisionEvidenceIndex index,
                                 AnalysisResult result) {
        if (result == null || result.evidence() == null || result.evidence().isEmpty()) return result;
        return new AnalysisResult(
                result.title(),
                result.conclusions(),
                result.evidence().stream().map(evidence -> enrich(context, index, evidence)).toList(),
                result.suggestions(),
                result.sections());
    }

    public AnalysisResult.Evidence enrich(VideoContext context,
                                          PrecisionEvidenceIndex index,
                                          AnalysisResult.Evidence evidence) {
        if (evidence == null) return null;
        if (evidence.timestampPrecision() == TimestampPrecision.SECOND
                && matchesVerifiedPrecision(context, index, evidence)) {
            return evidence;
        }
        VideoContext.VideoSegment window = context == null ? null : context.segments().stream()
                .filter(segment -> evidence.timestampMs() >= segment.startMs()
                        && evidence.timestampMs() < segment.endMs())
                .findFirst()
                .orElse(null);
        long fallbackTimestamp = window == null ? evidence.timestampMs() : window.startMs();
        if (context == null || index == null || index.evidences().isEmpty()
                || evidence.anchorText().isBlank()
                || evidence.source().equalsIgnoreCase("ASR+OCR")) {
            return minute(evidence, fallbackTimestamp);
        }
        if (window == null) return minute(evidence, fallbackTimestamp);

        String source = evidence.source().trim().toUpperCase(Locale.ROOT);
        PrecisionEvidence match = index.evidences().stream()
                .filter(candidate -> candidate.source().equalsIgnoreCase(source))
                .filter(candidate -> candidate.startMs() >= window.startMs()
                        && candidate.startMs() < window.endMs())
                .filter(candidate -> contains(candidate.text(), evidence.anchorText()))
                .sorted(java.util.Comparator.comparingLong(PrecisionEvidence::startMs)
                        .thenComparing(PrecisionEvidence::source))
                .findFirst()
                .orElse(null);
        return match == null
                ? minute(evidence, fallbackTimestamp)
                : new AnalysisResult.Evidence(
                        match.startMs(), evidence.source(), evidence.content(), evidence.claim(),
                        evidence.anchorText(), TimestampPrecision.SECOND);
    }

    private boolean matchesVerifiedPrecision(VideoContext context,
                                             PrecisionEvidenceIndex index,
                                             AnalysisResult.Evidence evidence) {
        if (context == null || index == null || evidence.anchorText().isBlank()) return false;
        String source = evidence.source().trim().toUpperCase(Locale.ROOT);
        if (!source.equals("ASR") && !source.equals("OCR")) return false;
        boolean inWindow = context.segments().stream()
                .anyMatch(segment -> evidence.timestampMs() >= segment.startMs()
                        && evidence.timestampMs() < segment.endMs());
        if (!inWindow) return false;
        return index.evidences().stream()
                .filter(candidate -> candidate.source().equalsIgnoreCase(source))
                .filter(candidate -> candidate.startMs() == evidence.timestampMs())
                .anyMatch(candidate -> contains(candidate.text(), evidence.anchorText()));
    }

    private AnalysisResult.Evidence minute(AnalysisResult.Evidence evidence, long timestampMs) {
        return new AnalysisResult.Evidence(
                timestampMs, evidence.source(), evidence.content(), evidence.claim(),
                evidence.anchorText(), TimestampPrecision.MINUTE);
    }

    private boolean contains(String candidate, String anchor) {
        String normalizedCandidate = normalize(candidate);
        String normalizedAnchor = normalize(anchor);
        return !normalizedAnchor.isEmpty()
                && !normalizedCandidate.isEmpty()
                && normalizedCandidate.contains(normalizedAnchor);
    }

    public static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
