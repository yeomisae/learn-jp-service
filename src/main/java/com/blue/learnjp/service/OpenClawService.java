package com.blue.learnjp.service;

import com.blue.learnjp.config.OpenClawConfig;
import com.blue.learnjp.dto.AnalysisResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class OpenClawService {

    private static final Logger log = LoggerFactory.getLogger(OpenClawService.class);

    private final OpenClawConfig config;
    private final ObjectMapper objectMapper;

    public OpenClawService(OpenClawConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public AnalysisResult analyze(String sentence) {
        String url = config.baseUrl() + "/chat/completions";
        log.info("Analyzing sentence: {}, URL: {}", sentence, url);

        Map<String, Object> requestBody = chatRequest(sentence);

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String response = postJson(url, bodyJson);

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();

            if (content.contains("rate limit") || content.contains("Rate limit") || content.startsWith("⚠")) {
                throw new RateLimitException("Rate limit detected in analyze response: " + content.substring(0, Math.min(200, content.length())));
            }

            String json = extractJson(content);
            return objectMapper.readValue(json, AnalysisResult.class);
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to analyze sentence: {}", sentence, e);
            throw new RuntimeException("OpenClaw analysis failed", e);
        }
    }

    private String postJson(String url, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + config.apiKey());
        conn.setRequestProperty("x-openclaw-agent-id", "jp-analyzer");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(180000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        log.info("OpenClaw response status: {}", status);

        if (status == 429) {
            String error = conn.getErrorStream() != null
                ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
            throw new RateLimitException("API rate limit reached: " + error);
        }

        if (status != 200) {
            String error = conn.getErrorStream() != null
                ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
            throw new IOException("OpenClaw returned " + status + ": " + error);
        }

        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 단어를 형태소 분석하여 구성 요소로 분해한다.
     * jako NOT_FOUND인 단어에 대해 호출하여 더 작은 단위로 쪼갠다.
     * 예: "150人以上" → [{surface:"150人", lemma:"150人"}, {surface:"以上", lemma:"以上"}]
     *     "お世話になりました" → [{surface:"お世話", lemma:"お世話"}, {surface:"なる", lemma:"なる"}]
     * @return 분해된 단어 리스트. 분해 불가하면 빈 리스트.
     */
    public List<AnalysisResult.WordInfo> decompose(String word) {
        String url = config.baseUrl() + "/chat/completions";
        String prompt = "다음 일본어 표현을 형태소 단위로 분해해줘. " +
            "각 형태소의 surface(표층형), lemma(사전형), reading(히라가나)을 JSON 배열로 반환. " +
            "조사·助動詞 등 문법 요소도 포함해서 모든 형태소를 반환. " +
            "숫자+단위는 하나로 묶어서 (예: 150人 → 하나의 단어). " +
            "JSON 배열만 반환하고 다른 텍스트 없이.\n\n" +
            "표현: " + word;

        Map<String, Object> requestBody = chatRequest(prompt);

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String response = postJson(url, bodyJson);

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();

            if (content.contains("rate limit") || content.contains("Rate limit") || content.startsWith("⚠")) {
                log.warn("Rate limit in decompose for '{}'", word);
                return List.of();
            }

            String json = extractJson(content);
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray() || arr.isEmpty()) return List.of();

            List<AnalysisResult.WordInfo> results = new ArrayList<>();
            for (JsonNode node : arr) {
                results.add(new AnalysisResult.WordInfo(
                    node.path("surface").asText(""),
                    node.path("lemma").asText(""),
                    node.path("reading").asText(""),
                    node.path("pos").asText(""),
                    node.path("meaning").asText(""),
                    "", "", ""
                ));
            }

            // 분해 결과가 원본과 동일하면 (쪼개지 못함) 빈 리스트 반환
            if (results.size() == 1 && results.get(0).lemma().equals(word)) return List.of();
            if (results.isEmpty()) return List.of();

            log.info("Decomposed '{}' → {} parts: {}", word, results.size(),
                results.stream().map(AnalysisResult.WordInfo::lemma).toList());
            return results;
        } catch (Exception e) {
            log.warn("Failed to decompose '{}': {}", word, e.getMessage());
            return List.of();
        }
    }

    private String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n') + 1;
            int end = trimmed.lastIndexOf("```");
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }
        return trimmed;
    }

    private Map<String, Object> chatRequest(String content) {
        return Map.of(
            "model", configuredModel(),
            "messages", List.of(
                Map.of("role", "user", "content", content)
            )
        );
    }

    private String configuredModel() {
        String model = config.model();
        return model != null && !model.isBlank() ? model : "openclaw";
    }
}
