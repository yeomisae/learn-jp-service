package com.blue.learnjp.config;

public record CircuitBreakerConfig(
    int slidingWindowSize,
    int minimumNumberOfCalls,
    double failureRateThreshold,
    long openDurationMs
) {

    public CircuitBreakerConfig {
        slidingWindowSize = slidingWindowSize > 0 ? slidingWindowSize : 20;
        minimumNumberOfCalls = minimumNumberOfCalls > 0
            ? Math.min(minimumNumberOfCalls, slidingWindowSize)
            : Math.min(5, slidingWindowSize);
        failureRateThreshold = failureRateThreshold > 0 ? failureRateThreshold : 50.0;
        openDurationMs = openDurationMs > 0 ? openDurationMs : 30_000;
    }

    public static CircuitBreakerConfig defaults(int slidingWindowSize, int minimumNumberOfCalls,
                                                double failureRateThreshold, long openDurationMs) {
        return new CircuitBreakerConfig(slidingWindowSize, minimumNumberOfCalls, failureRateThreshold, openDurationMs);
    }
}
