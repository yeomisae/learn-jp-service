package com.blue.learnjp.service;

import com.blue.learnjp.config.OpenClawConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenClawServiceTests {

    @Test
    void analyzeUsesConfiguredModelInRequestPayload() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"choices":[{"message":{"content":"{\\"words\\":[],\\"edges\\":[]}"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OpenClawService service = new OpenClawService(
                new OpenClawConfig(baseUrl, "test-api-key", "custom-model"),
                new ObjectMapper()
            );

            service.analyze("寿司を食べる");

            JsonNode payload = new ObjectMapper().readTree(requestBody.get());
            assertThat(payload.path("model").asText()).isEqualTo("custom-model");
            assertThat(payload.path("messages").get(0).path("content").asText()).isEqualTo("寿司を食べる");
        } finally {
            server.stop(0);
        }
    }
}
