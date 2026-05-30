package com.blue.learnjp.service;

import com.blue.learnjp.config.OpenClawConfig;
import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.http.CircuitBreakerOpenException;
import com.blue.learnjp.http.ResilientCallExecutor;
import com.blue.learnjp.http.RetryableExternalServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class OpenClawService {

    private static final Logger log = LoggerFactory.getLogger(OpenClawService.class);

    private final OpenClawConfig config;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private final ResilientCallExecutor callExecutor;

    public OpenClawService(OpenClawConfig config,
                           ObjectMapper objectMapper,
                           @Qualifier("openClawHttpClient") CloseableHttpClient httpClient,
                           @Qualifier("openClawCallExecutor") ResilientCallExecutor callExecutor) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.callExecutor = callExecutor;
    }

    public AnalysisResult analyze(String sentence) {
        log.info("Analyzing sentence via OpenClaw: {}", sentence);

        Map<String, Object> requestBody = chatRequest(analysisPrompt(sentence));

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String response = callExecutor.execute("chat.completions.analyze", () -> postJson(bodyJson));

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();

            if (content.contains("rate limit") || content.contains("Rate limit") || content.startsWith("⚠")) {
                throw new RateLimitException("Rate limit detected in analyze response: " + content.substring(0, Math.min(200, content.length())));
            }

            String json = extractJson(content);
            try {
                return objectMapper.readValue(json, AnalysisResult.class);
            } catch (Exception parseFailure) {
                throw new IllegalArgumentException(
                    "OpenClaw returned non-JSON analysis content: " + truncate(content),
                    parseFailure
                );
            }
        } catch (RateLimitException | RetryableExternalServiceException | CircuitBreakerOpenException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to analyze sentence: {}", sentence, e);
            throw new RuntimeException("OpenClaw analysis failed: " + rootMessage(e), e);
        }
    }

    private String postJson(String body) throws IOException {
        HttpPost request = new HttpPost(config.baseUrl() + "/chat/completions");
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Authorization", "Bearer " + config.apiKey());
        request.setHeader("x-openclaw-agent-id", "jp-analyzer");
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getCode();
            String payload = readEntity(response);

            log.info("OpenClaw response status: {}", status);

            if (status == 429) {
                throw new RateLimitException("OpenClaw rate limit reached: " + truncate(payload));
            }
            if (status == 408 || status >= 500) {
                throw new RetryableExternalServiceException(
                    "OpenClaw returned retryable status " + status + ": " + truncate(payload),
                    false
                );
            }
            if (status != 200) {
                throw new IllegalStateException("OpenClaw returned " + status + ": " + truncate(payload));
            }

            return payload;
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
        String prompt = "다음 일본어 표현을 형태소 단위로 분해해줘. " +
            "각 형태소의 surface(표층형), lemma(사전형), reading(히라가나)을 JSON 배열로 반환. " +
            "조사·助動詞 등 문법 요소도 포함해서 모든 형태소를 반환. " +
            "숫자+단위는 하나로 묶어서 (예: 150人 → 하나의 단어). " +
            "JSON 배열만 반환하고 다른 텍스트 없이.\n\n" +
            "표현: " + word;

        Map<String, Object> requestBody = chatRequest(prompt);

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String response = callExecutor.execute("chat.completions.decompose", () -> postJson(bodyJson));

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
        } catch (RateLimitException | RetryableExternalServiceException | CircuitBreakerOpenException e) {
            log.warn("Transient failure while decomposing '{}': {}", word, e.getMessage());
            return List.of();
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

    private String analysisPrompt(String sentence) {
        return """
            Analyze the Japanese input and return ONLY valid JSON.
            Do not explain, rewrite, translate, ask questions, or return plain text.
            If the input is too short, fragmentary, idiomatic, ambiguous, or cannot be analyzed, return exactly:
            {"words":[],"edges":[]}

            JSON schema:
            {
              "words": [
                {
                  "surface": "string",
                  "lemma": "dictionary form string",
                  "reading": "hiragana or katakana reading",
                  "pos": "part of speech",
                  "meaning": "short Korean meaning",
                  "synonyms": "",
                  "antonyms": "",
                  "description": ""
                }
              ],
              "edges": [
                {
                  "from": "lemma of source word",
                  "to": "lemma of target word",
                  "pattern": "short co-occurrence relation pattern"
                }
              ]
            }

            Rules:
            - Include only Japanese content words in words.
            - Edges must only reference lemmas present in words.
            - Return {"words":[],"edges":[]} instead of any non-JSON response.

            Input:
            %s
            """.formatted(sentence);
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        return truncate(message);
    }

    private String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= 400 ? text : text.substring(0, 400);
    }

    private String readEntity(CloseableHttpResponse response) throws IOException {
        if (response.getEntity() == null) {
            return "";
        }
        return new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
    }
}
