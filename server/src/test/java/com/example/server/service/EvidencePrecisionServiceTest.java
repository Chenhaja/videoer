package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.PrecisionEvidence;
import com.example.server.dto.PrecisionEvidenceIndex;
import com.example.server.dto.TimestampPrecision;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidencePrecisionServiceTest {

    private final EvidencePrecisionService service = new EvidencePrecisionService();
    private final VideoContext context = new VideoContext(
            "video.mp4", "goal",
            List.of(new VideoContext.VideoSegment(
                    0, 60_000, "模型通过反向传播更新参数", List.of("参数更新"), List.of())));

    @Test
    void resolvesEarliestAsrSentenceInsideTheSelectedWindow() {
        AnalysisResult.Evidence evidence = service.enrich(
                context,
                new PrecisionEvidenceIndex(List.of(
                        PrecisionEvidence.asr(18_000, 22_000, "模型通过反向传播更新参数"),
                        PrecisionEvidence.asr(35_000, 39_000, "模型通过反向传播更新参数"))),
                new AnalysisResult.Evidence(0, "ASR", "完整窗口文本", "结论", "模型通过反向传播更新参数"));

        assertEquals(18_000, evidence.timestampMs());
        assertEquals(TimestampPrecision.SECOND, evidence.timestampPrecision());
    }

    @Test
    void resolvesOcrFrameAndKeepsMixedSourceAtMinutePrecision() {
        AnalysisResult.Evidence ocr = service.enrich(
                context,
                new PrecisionEvidenceIndex(List.of(PrecisionEvidence.ocr(42_500, "参数更新", "frame.jpg"))),
                new AnalysisResult.Evidence(0, "OCR", "参数更新", "结论", "参数更新"));
        AnalysisResult.Evidence mixed = service.enrich(
                context,
                new PrecisionEvidenceIndex(List.of(PrecisionEvidence.asr(18_000, 22_000, "模型通过反向传播更新参数"))),
                new AnalysisResult.Evidence(0, "ASR+OCR", "完整窗口文本", "结论", "模型通过反向传播更新参数"));

        assertEquals(42_500, ocr.timestampMs());
        assertEquals(TimestampPrecision.SECOND, ocr.timestampPrecision());
        assertEquals(0, mixed.timestampMs());
        assertEquals(TimestampPrecision.MINUTE, mixed.timestampPrecision());
    }

    @Test
    void fallsBackForBlankOrUnmatchedAnchorsAndMissingIndex() {
        AnalysisResult.Evidence blank = new AnalysisResult.Evidence(
                0, "ASR", "完整窗口文本", "结论", "");
        AnalysisResult.Evidence unmatched = new AnalysisResult.Evidence(
                0, "OCR", "完整窗口文本", "结论", "不存在");

        assertEquals(TimestampPrecision.MINUTE,
                service.enrich(context, PrecisionEvidenceIndex.empty(), blank).timestampPrecision());
        assertEquals(TimestampPrecision.MINUTE,
                service.enrich(context, new PrecisionEvidenceIndex(List.of(
                        PrecisionEvidence.ocr(42_500, "参数更新", "frame.jpg"))), unmatched)
                        .timestampPrecision());
    }

    @Test
    void fallbackUsesTheContainingWindowStart() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                42_500, "ASR", "完整窗口文本", "结论", "不存在");

        AnalysisResult.Evidence enriched = service.enrich(
                context, PrecisionEvidenceIndex.empty(), evidence);

        assertEquals(0, enriched.timestampMs());
        assertEquals(TimestampPrecision.MINUTE, enriched.timestampPrecision());
    }

    @Test
    void secondPrecisionIsIdempotent() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                35_000, TimestampPrecision.SECOND, "ASR", "完整窗口文本",
                "模型通过反向传播更新参数", "结论");

        AnalysisResult.Evidence enriched = service.enrich(
                context,
                new PrecisionEvidenceIndex(List.of(
                        PrecisionEvidence.asr(18_000, 22_000, "模型通过反向传播更新参数"),
                        PrecisionEvidence.asr(35_000, 39_000, "模型通过反向传播更新参数"))),
                evidence);

        assertEquals(evidence, enriched);
    }

    @Test
    void doesNotTrustUnverifiedSecondPrecision() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                35_000, TimestampPrecision.SECOND, "ASR", "完整窗口文本",
                "不存在的锚点", "结论");

        AnalysisResult.Evidence enriched = service.enrich(
                context,
                new PrecisionEvidenceIndex(List.of(
                        PrecisionEvidence.asr(35_000, 39_000, "模型通过反向传播更新参数"))),
                evidence);

        assertEquals(0, enriched.timestampMs());
        assertEquals(TimestampPrecision.MINUTE, enriched.timestampPrecision());
    }
}
