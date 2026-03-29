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

        Map<String, Object> requestBody = Map.of(
            "model", "openclaw",
            "messages", List.of(
                Map.of("role", "user", "content", sentence)
            )
        );

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String response = postJson(url, bodyJson);

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();

            String json = extractJson(content);
            return objectMapper.readValue(json, AnalysisResult.class);
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
     * 단어 목록의 품질 보완 정보를 OpenClaw에게 요청한다.
     * 배치 단위로 한글 meaning, pos, synonyms, antonyms, description을 채운다.
     */
    public List<Map<String, String>> enrich(List<Map<String, Object>> words) {
        long startTime = System.currentTimeMillis();
        log.info("Enrich started for {} words", words.size());
        StringBuilder prompt = new StringBuilder();
        prompt.append("아래 일본어 단어들의 정보를 JSON 배열로 반환해줘.\n");
        prompt.append("각 단어마다 반드시 다음 필드를 채워줘:\n");
        prompt.append("- lemma: 원형 (그대로 유지)\n");
        prompt.append("- meaning: 한국어 뜻 (영어가 아닌 한국어로)\n");
        prompt.append("- pos: 품사 (名詞, 動詞, 形容詞, 副詞, 助詞, 接続詞, 感動詞, 連体詞, 助動詞 등 일본어 품사명)\n");
        prompt.append("- synonyms: 일본어 유의어 (쉼표 구분, 없으면 빈 문자열)\n");
        prompt.append("- antonyms: 일본어 반의어 (쉼표 구분, 없으면 빈 문자열)\n");
        prompt.append("- description: 한국어 설명 (한 줄)\n\n");
        prompt.append("단어 목록:\n");

        for (Map<String, Object> w : words) {
            String lemma = (String) w.get("lemma");
            String meaning = (String) w.get("meaning");
            prompt.append("- ").append(lemma);
            if (meaning != null && !meaning.isBlank()) {
                prompt.append(" (현재 meaning: ").append(meaning).append(")");
            }
            prompt.append("\n");
        }

        prompt.append("\nJSON 배열만 반환해. 다른 텍스트 없이.");

        String url = config.baseUrl() + "/chat/completions";

        Map<String, Object> requestBody = Map.of(
            "model", "openclaw",
            "messages", List.of(
                Map.of("role", "user", "content", prompt.toString())
            )
        );

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            long apiStart = System.currentTimeMillis();
            String response;
            try {
                response = postJson(url, bodyJson);
            } catch (java.net.SocketTimeoutException e) {
                log.warn("OpenClaw timeout after {}ms, retrying once...", System.currentTimeMillis() - apiStart);
                apiStart = System.currentTimeMillis();
                response = postJson(url, bodyJson);
            }
            log.info("OpenClaw enrich API took {}ms for {} words", System.currentTimeMillis() - apiStart, words.size());

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();

            if (content.contains("rate limit") || content.contains("Rate limit") || content.contains("⚠️")) {
                throw new RateLimitException("Rate limit detected in response body: " + content);
            }

            String json = extractJson(content);

            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) {
                throw new RuntimeException("Expected JSON array but got: " + json.substring(0, Math.min(100, json.length())));
            }

            List<Map<String, String>> results = new ArrayList<>();
            for (JsonNode node : arr) {
                Map<String, String> map = new HashMap<>();
                map.put("lemma", node.path("lemma").asText(""));
                map.put("meaning", node.path("meaning").asText(""));
                map.put("pos", node.path("pos").asText(""));
                map.put("synonyms", node.path("synonyms").asText(""));
                map.put("antonyms", node.path("antonyms").asText(""));
                map.put("description", node.path("description").asText(""));
                results.add(map);
            }
            log.info("Enrich completed in {}ms. {} words processed", System.currentTimeMillis() - startTime, results.size());
            return results;
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to enrich words after {}ms", System.currentTimeMillis() - startTime, e);
            throw new RuntimeException("OpenClaw enrichment failed", e);
        }
    }

    /**
     * 기존 DB 데이터와 새 분석 결과를 비교하여 의미적 중복을 제거한 최종 값을 반환한다.
     * 기존 데이터가 있는 단어만 대상으로 하며, 다의어의 새로운 뜻은 보존한다.
     */
    public List<AnalysisResult.WordInfo> reconcile(
            List<AnalysisResult.WordInfo> newWords,
            Map<String, Map<String, Object>> existingWords) {

        StringBuilder prompt = new StringBuilder();
        prompt.append("아래 단어들의 기존 DB 값과 새 분석 값을 비교해서, 의미적 중복을 제거한 최종 값을 JSON 배열로 반환해줘.\n\n");
        prompt.append("규칙:\n");
        prompt.append("- 의미적으로 같은 표현은 하나만 남겨 (더 자연스러운 한국어 표현 선택)\n");
        prompt.append("- 진짜 새로운 뜻/품사는 추가 (다의어 고려)\n");
        prompt.append("- 각 필드는 쉼표 구분 문자열로 반환\n");
        prompt.append("- 반환 필드: lemma, surface, meaning, pos, synonyms, antonyms, description\n\n");

        for (AnalysisResult.WordInfo w : newWords) {
            Map<String, Object> existing = existingWords.get(w.lemma());
            if (existing == null) continue;

            prompt.append("---\n");
            prompt.append("lemma: ").append(w.lemma()).append("\n");
            prompt.append("기존 surface: ").append(nullSafe(existing.get("surface"))).append("\n");
            prompt.append("새 surface: ").append(nullSafe(w.surface())).append("\n");
            prompt.append("기존 meaning: ").append(nullSafe(existing.get("meaning"))).append("\n");
            prompt.append("새 meaning: ").append(nullSafe(w.meaning())).append("\n");
            prompt.append("기존 pos: ").append(nullSafe(existing.get("pos"))).append("\n");
            prompt.append("새 pos: ").append(nullSafe(w.pos())).append("\n");
            prompt.append("기존 synonyms: ").append(nullSafe(existing.get("synonyms"))).append("\n");
            prompt.append("새 synonyms: ").append(nullSafe(w.synonyms())).append("\n");
            prompt.append("기존 antonyms: ").append(nullSafe(existing.get("antonyms"))).append("\n");
            prompt.append("새 antonyms: ").append(nullSafe(w.antonyms())).append("\n");
            prompt.append("기존 description: ").append(nullSafe(existing.get("description"))).append("\n");
            prompt.append("새 description: ").append(nullSafe(w.description())).append("\n\n");
        }

        prompt.append("JSON 배열만 반환해. 다른 텍스트 없이.");

        String url = config.baseUrl() + "/chat/completions";
        Map<String, Object> requestBody = Map.of(
            "model", "openclaw",
            "messages", List.of(
                Map.of("role", "user", "content", prompt.toString())
            )
        );

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            long start = System.currentTimeMillis();
            String response = postJson(url, bodyJson);
            log.info("OpenClaw reconcile took {}ms for {} words", System.currentTimeMillis() - start, newWords.size());

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();
            String json = extractJson(content);

            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) {
                throw new RuntimeException("Expected JSON array from reconcile: " + json.substring(0, Math.min(100, json.length())));
            }

            List<AnalysisResult.WordInfo> results = new ArrayList<>();
            for (JsonNode node : arr) {
                results.add(new AnalysisResult.WordInfo(
                    node.path("surface").asText(""),
                    node.path("lemma").asText(""),
                    "", // reading은 reconcile 대상 아님
                    node.path("pos").asText(""),
                    node.path("meaning").asText(""),
                    node.path("synonyms").asText(""),
                    node.path("antonyms").asText(""),
                    node.path("description").asText(""),
                    ""  // jlptLevel은 reconcile 대상 아님
                ));
            }
            return results;
        } catch (Exception e) {
            log.error("Reconcile failed: {}", e.getMessage(), e);
            throw new RuntimeException("OpenClaw reconcile failed", e);
        }
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : value.toString();
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
}
