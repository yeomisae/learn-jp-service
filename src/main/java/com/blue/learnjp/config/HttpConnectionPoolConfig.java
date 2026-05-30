package com.blue.learnjp.config;

public record HttpConnectionPoolConfig(
    int maxTotal,
    int maxPerRoute,
    int validateAfterInactivityMs,
    int idleEvictMs
) {

    public HttpConnectionPoolConfig {
        maxTotal = positiveOrDefault(maxTotal, 50);
        maxPerRoute = positiveOrDefault(maxPerRoute, Math.min(maxTotal, 20));
        validateAfterInactivityMs = positiveOrDefault(validateAfterInactivityMs, 5_000);
        idleEvictMs = positiveOrDefault(idleEvictMs, 30_000);
    }

    public static HttpConnectionPoolConfig defaults(int maxTotal, int maxPerRoute,
                                                    int validateAfterInactivityMs, int idleEvictMs) {
        return new HttpConnectionPoolConfig(maxTotal, maxPerRoute, validateAfterInactivityMs, idleEvictMs);
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
