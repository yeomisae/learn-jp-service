package com.blue.learnjp.config;

public record RetryConfig(
    int maxAttempts,
    long initialBackoffMs,
    long maxBackoffMs,
    double multiplier
) {

    public RetryConfig {
        maxAttempts = maxAttempts > 0 ? maxAttempts : 3;
        initialBackoffMs = initialBackoffMs > 0 ? initialBackoffMs : 500;
        maxBackoffMs = maxBackoffMs >= initialBackoffMs ? maxBackoffMs : Math.max(initialBackoffMs, 5_000);
        multiplier = multiplier >= 1.0 ? multiplier : 2.0;
    }

    public static RetryConfig defaults(int maxAttempts, long initialBackoffMs,
                                       long maxBackoffMs, double multiplier) {
        return new RetryConfig(maxAttempts, initialBackoffMs, maxBackoffMs, multiplier);
    }

    public long backoffForAttempt(int retryNumber) {
        if (retryNumber <= 0) {
            return initialBackoffMs;
        }

        double candidate = initialBackoffMs * Math.pow(multiplier, retryNumber - 1);
        return Math.min(maxBackoffMs, Math.max(initialBackoffMs, Math.round(candidate)));
    }
}
