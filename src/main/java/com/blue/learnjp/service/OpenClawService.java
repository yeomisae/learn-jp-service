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
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        log.info("OpenClaw response status: {}", status);

        if (status != 200) {
            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("OpenClaw returned " + status + ": " + error);
        }

        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 단어 목록의 품질 보완 정보를 OpenClaw에게 요청한다.
     * 배치 단위로 한글 meaning, pos, synonyms, antonyms, description을 채운다.
     */
    public List<Map<String, String>> enrich(List<Map<String, Object>> words) {
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
            String response = postJson(url, bodyJson);

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();
            String json = extractJson(content);

            JsonNode arr = objectMapper.readTree(json);
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
            return results;
        } catch (Exception e) {
            log.error("Failed to enrich words", e);
            throw new RuntimeException("OpenClaw enrichment failed", e);
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
