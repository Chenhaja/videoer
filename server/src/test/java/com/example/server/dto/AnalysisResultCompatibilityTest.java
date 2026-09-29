package com.example.server.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalysisResultCompatibilityTest {

    @Test
    void oldEvidencePayloadDefaultsToMinutePrecisionAndEmptyAnchor() throws Exception {
        AnalysisResult result = new ObjectMapper().readValue("""
                {
                  "title": "旧结果",
                  "conclusions": ["结论"],
                  "evidence": [
                    {"timestampMs": 60000, "source": "ASR", "content": "原文", "claim": "结论"}
                  ],
                  "suggestions": []
                }
                """, AnalysisResult.class);

        assertEquals("", result.evidence().get(0).anchorText());
        assertEquals(TimestampPrecision.MINUTE, result.evidence().get(0).timestampPrecision());
    }
}
