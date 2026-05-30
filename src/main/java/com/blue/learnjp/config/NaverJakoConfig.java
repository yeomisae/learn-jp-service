package com.blue.learnjp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "naver.jako")
public record NaverJakoConfig(
    String baseUrl,
    int delayMs,
    int timeoutMs,
    int maxConcurrent,
    HttpTimeoutConfig timeout,
    HttpConnectionPoolConfig pool,
    RetryConfig retry,
    CircuitBreakerConfig circuitBreaker
) {

    public NaverJakoConfig {
        delayMs = delayMs > 0 ? delayMs : 200;
        timeoutMs = timeoutMs > 0 ? timeoutMs : 5_000;
        maxConcurrent = maxConcurrent > 0 ? maxConcurrent : 1;
        timeout = timeout != null ? timeout : HttpTimeoutConfig.defaults(2_000, timeoutMs, timeoutMs, 1_000);
        pool = pool != null ? pool : HttpConnectionPoolConfig.defaults(20, Math.max(2, maxConcurrent), 5_000, 30_000);
        retry = retry != null ? retry : RetryConfig.defaults(3, 300, 3_000, 2.0);
        circuitBreaker = circuitBreaker != null ? circuitBreaker : CircuitBreakerConfig.defaults(20, 5, 50.0, 30_000);
    }
}
