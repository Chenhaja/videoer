package com.example.server.dto;

import java.util.Comparator;
import java.util.List;

/** Media-level sidecar kept out of the Agent VideoContext prompt. */
public record PrecisionEvidenceIndex(List<PrecisionEvidence> evidences) {
    public PrecisionEvidenceIndex {
        evidences = evidences == null ? List.of() : evidences.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingLong(PrecisionEvidence::startMs)
                        .thenComparing(PrecisionEvidence::source))
                .toList();
    }

    public static PrecisionEvidenceIndex empty() {
        return new PrecisionEvidenceIndex(List.of());
    }
}
