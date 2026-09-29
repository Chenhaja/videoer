package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.PrecisionEvidence;
import com.example.server.dto.PrecisionEvidenceIndex;
import com.example.server.dto.TimestampPrecision;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceVerificationServiceTest {

    private final EvidenceVerificationService service = new EvidenceVerificationService();
    private final VideoContext context = new VideoContext(
            "lesson.mp4",
            "总结课程",
            List.of(new VideoContext.VideoSegment(
                    120_000,
                    180_000,
                    "接下来讲解二叉树的前序遍历",
                    List.of("前序遍历：根节点、左子树、右子树"),
                    List.of("frame_000125.jpg"))));

    @Test
    void acceptsVerbatimEvidenceAtTheDeclaredTimestamp() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "根节点、左子树、右子树", "前序遍历顺序");

        assertTrue(service.supported(context, evidence));
        assertTrue(service.supportsClaim(context, "前序遍历顺序", evidence));
    }

    @Test
    void rejectsTextThatOnlyLooksSimilarToTheSource() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "根节点左子树不存在，因此应跳过", "前序遍历顺序");

        assertFalse(service.supported(context, evidence));
    }

    @Test
    void secondPrecisionRequiresTheMatchingPrecisionIndex() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, TimestampPrecision.SECOND, "OCR", "窗口文本",
                "根节点、左子树、右子树", "前序遍历顺序");
        PrecisionEvidenceIndex index = new PrecisionEvidenceIndex(List.of(
                PrecisionEvidence.ocr(125_000, "根节点、左子树、右子树", "frame.jpg")));

        assertTrue(service.supported(context, evidence, index));
        assertFalse(service.supported(context, evidence, PrecisionEvidenceIndex.empty()));
    }
}
