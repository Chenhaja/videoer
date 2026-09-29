package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AliyunAsrUtilsTest {

    @Test
    void parsesOptionalSentenceSpansAndConvertsSecondsToMilliseconds() {
        AliyunAsrUtils.Transcription result = AliyunAsrUtils.parseTranscription(JSON.parseObject("""
                {
                  "text": "第一句 第二句",
                  "segments": [
                    {"text": "第一句", "start": 1.25, "end": 3.5},
                    {"text": "第二句", "start": 3.5, "end": 5.0}
                  ]
                }
                """));

        assertEquals("第一句 第二句", result.text());
        assertEquals(2, result.sentences().size());
        assertEquals(1_250, result.sentences().get(0).startMs());
        assertEquals(3_500, result.sentences().get(0).endMs());
    }

    @Test
    void missingSegmentsKeepTheLegacyTextOnlyPath() {
        AliyunAsrUtils.Transcription result = AliyunAsrUtils.parseTranscription(
                JSON.parseObject("{\"text\":\"只有整段文本\"}"));

        assertEquals("只有整段文本", result.text());
        assertEquals(0, result.sentences().size());
    }
}
