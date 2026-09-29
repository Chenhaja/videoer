package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.PrecisionEvidence;
import com.example.server.dto.PrecisionEvidenceIndex;
import com.example.server.dto.TimestampPrecision;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class EvidenceVerificationService {

    public boolean timestampCovered(VideoContext context, AnalysisResult.Evidence evidence) {
        return context != null && evidence != null && context.segments().stream()
                .anyMatch(segment -> containsTimestamp(segment, evidence.timestampMs()));
    }

    public boolean timestampCovered(VideoContext context,
                                    AnalysisResult.Evidence evidence,
                                    PrecisionEvidenceIndex index) {
        if (evidence != null && evidence.timestampPrecision() == TimestampPrecision.SECOND) {
            return supported(context, evidence, index);
        }
        return timestampCovered(context, evidence);
    }

    public boolean supported(VideoContext context, AnalysisResult.Evidence evidence) {
        if (context == null || evidence == null || evidence.content().isBlank()) return false;
        if (evidence.timestampPrecision() == TimestampPrecision.SECOND) return false;
        String source = evidence.source().toUpperCase(Locale.ROOT);
        if (!source.contains("ASR") && !source.contains("OCR")) return false;

        return context.segments().stream()
                .filter(segment -> containsTimestamp(segment, evidence.timestampMs()))
                .map(segment -> sourceText(segment, source))
                .anyMatch(candidate -> textMatches(evidence.content(), candidate));
    }

    public boolean supported(VideoContext context,
                             AnalysisResult.Evidence evidence,
                             PrecisionEvidenceIndex index) {
        if (evidence == null || evidence.timestampPrecision() != TimestampPrecision.SECOND) {
            return supported(context, evidence);
        }
        if (context == null || index == null || evidence.anchorText().isBlank()) return false;
        String source = evidence.source().toUpperCase(Locale.ROOT);
        if (!source.equals("ASR") && !source.equals("OCR")) return false;
        VideoContext.VideoSegment window = context.segments().stream()
                .filter(segment -> evidence.timestampMs() >= segment.startMs()
                        && evidence.timestampMs() < segment.endMs())
                .findFirst().orElse(null);
        if (window == null) return false;
        return index.evidences().stream()
                .filter(candidate -> candidate.source().equalsIgnoreCase(source))
                .filter(candidate -> candidate.startMs() >= window.startMs()
                        && candidate.startMs() < window.endMs())
                .filter(candidate -> candidate.startMs() == evidence.timestampMs())
                .anyMatch(candidate -> textMatches(evidence.anchorText(), candidate.text()));
    }

    public boolean supportsClaim(VideoContext context,
                                 String claim,
                                 AnalysisResult.Evidence evidence) {
        return evidence != null
                && !normalize(claim).isEmpty()
                && normalize(claim).equals(normalize(evidence.claim()))
                && supported(context, evidence);
    }

    public boolean supportsClaim(VideoContext context,
                                 String claim,
                                 AnalysisResult.Evidence evidence,
                                 PrecisionEvidenceIndex index) {
        return evidence != null
                && !normalize(claim).isEmpty()
                && normalize(claim).equals(normalize(evidence.claim()))
                && supported(context, evidence, index);
    }

    private boolean containsTimestamp(VideoContext.VideoSegment segment, long timestampMs) {
        return timestampMs >= segment.startMs() && timestampMs < segment.endMs();
    }

    private String sourceText(VideoContext.VideoSegment segment, String source) {
        if (source.contains("ASR") && source.contains("OCR")) {
            return segment.transcript() + " " + String.join(" ", segment.ocrTexts());
        }
        if (source.contains("ASR")) return segment.transcript();
        return String.join(" ", segment.ocrTexts());
    }

    private boolean textMatches(String evidence, String candidate) {
        String normalizedEvidence = normalize(evidence);
        String normalizedCandidate = normalize(candidate);
        return !normalizedEvidence.isEmpty()
                && !normalizedCandidate.isEmpty()
                && normalizedCandidate.contains(normalizedEvidence);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
