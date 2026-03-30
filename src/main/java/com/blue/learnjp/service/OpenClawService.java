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

        Map<String, Object> requestBody = Map.of(
            "model", "openclaw",
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            )
        );

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
}
