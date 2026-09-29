package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class AliyunAsrUtils {

    private static final Logger log = LoggerFactory.getLogger(AliyunAsrUtils.class);
    private static final int MAX_ATTEMPTS = 3;

    private final String apiKey;
    private final String transcriptionUrl;
    private final String model;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(3, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build();

    public AliyunAsrUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                          @Value("${ai.asr.url}") String transcriptionUrl,
                          @Value("${ai.asr.model}") String model) {
        this.apiKey = apiKey;
        this.transcriptionUrl = transcriptionUrl;
        this.model = model;
    }

    public String audioToText(String filePath) {
        return audioToTextWithSegments(filePath).text();
    }

    /**
     * Reads optional provider sentence spans. Providers that only return text remain fully
     * supported; the caller simply receives an empty segment list.
     */
    public Transcription audioToTextWithSegments(String filePath) {
        File file = new File(filePath);
        // 这里的音频是本流水线上一步用 ffmpeg 切出来的，缺失属于「意外状态」而非「调用方参数错误」，
        // 重跑流水线可以重新生成，因此用 IllegalStateException 保持它可重试，
        // 不要和下面表示「请求本身不合法、重试无意义」的 IllegalArgumentException 混用。
        if (!file.isFile()) throw new IllegalStateException("ASR audio file does not exist");

        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Transcription transcription = execute(file);
                if (transcription.text() == null || transcription.text().isBlank()) {
                    throw new IllegalStateException("ASR 返回空文本");
                }
                return transcription;
            } catch (IOException e) {
                lastError = e;
                log.warn("asr_attempt_failed attempt={} file={}", attempt + 1, file.getName(), e);
                if (attempt < MAX_ATTEMPTS - 1) waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("ASR 调用失败，已达到最大重试次数", lastError);
    }

    private Transcription execute(File file) throws IOException {
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(file, MediaType.parse("application/octet-stream")))
                .addFormDataPart("model", model)
                .build();
        Request request = new Request.Builder()
                .url(transcriptionUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (response.isSuccessful()) {
                JSONObject json = JSON.parseObject(body);
                return parseTranscription(json);
            }
            if (response.code() == 429 || response.code() >= 500) {
                throw new RetryableAsrException("ASR transient HTTP " + response.code());
            }
            // 非 429/5xx 的失败源于请求本身（参数、鉴权、音频格式不支持），重投多少次都不会变好。
            // 用 IllegalArgumentException 让上层能识别为「不可重试」，避免整条流水线空转重试。
            throw new IllegalArgumentException("ASR request rejected with HTTP " + response.code());
        }
    }

    static Transcription parseTranscription(JSONObject json) {
        String text = json == null ? "" : json.getString("text");
        List<Sentence> sentences = new ArrayList<>();
        Object rawSegments = json == null ? null : json.get("segments");
        if (rawSegments instanceof JSONArray segments) {
            segments.forEach(item -> {
                if (!(item instanceof JSONObject segment)) return;
                String segmentText = segment.getString("text");
                Number start = number(segment, "start", "start_time", "startTime");
                Number end = number(segment, "end", "end_time", "endTime");
                if (segmentText == null || segmentText.isBlank() || start == null || end == null) return;
                long startMs = toMillis(start);
                long endMs = toMillis(end);
                if (startMs >= 0 && endMs > startMs) {
                    sentences.add(new Sentence(startMs, endMs, segmentText.trim()));
                }
            });
        }
        return new Transcription(text, sentences);
    }

    private static Number number(JSONObject object, String... names) {
        for (String name : names) {
            Object value = object.get(name);
            if (value instanceof Number number) return number;
        }
        return null;
    }

    private static long toMillis(Number value) {
        double seconds = value.doubleValue();
        if (!Double.isFinite(seconds) || seconds < 0) return -1;
        return Math.round(seconds * 1000);
    }

    public record Transcription(String text, List<Sentence> sentences) {
        public Transcription {
            text = text == null ? "" : text.trim();
            sentences = sentences == null ? List.of() : List.copyOf(sentences);
        }
    }

    public record Sentence(long startMs, long endMs, String text) {
        public Sentence {
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid ASR sentence range");
            text = text == null ? "" : text.trim();
        }
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(1_000L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR retry interrupted", e);
        }
    }

    private static class RetryableAsrException extends IOException {
        private RetryableAsrException(String message) {
            super(message);
        }
    }
}
