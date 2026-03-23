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
import java.util.List;
import java.util.Map;

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
