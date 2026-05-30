package com.blue.learnjp.config;

public record HttpTimeoutConfig(
    int connectMs,
    int readMs,
    int responseMs,
    int connectionRequestMs
) {

    public HttpTimeoutConfig {
        connectMs = positiveOrDefault(connectMs, 3_000);
        readMs = positiveOrDefault(readMs, 30_000);
        responseMs = positiveOrDefault(responseMs, readMs);
        connectionRequestMs = positiveOrDefault(connectionRequestMs, 2_000);
    }

    public static HttpTimeoutConfig defaults(int connectMs, int readMs, int responseMs, int connectionRequestMs) {
        return new HttpTimeoutConfig(connectMs, readMs, responseMs, connectionRequestMs);
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
