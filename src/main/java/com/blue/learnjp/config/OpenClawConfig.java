package com.blue.learnjp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openclaw")
public record OpenClawConfig(
    String baseUrl,
    String apiKey,
    String model,
    HttpTimeoutConfig timeout,
    HttpConnectionPoolConfig pool,
    RetryConfig retry,
    CircuitBreakerConfig circuitBreaker
) {

    public OpenClawConfig {
        timeout = timeout != null ? timeout : HttpTimeoutConfig.defaults(3_000, 180_000, 180_000, 2_000);
        pool = pool != null ? pool : HttpConnectionPoolConfig.defaults(50, 20, 5_000, 30_000);
        retry = retry != null ? retry : RetryConfig.defaults(3, 500, 5_000, 2.0);
        circuitBreaker = circuitBreaker != null ? circuitBreaker : CircuitBreakerConfig.defaults(20, 5, 50.0, 30_000);
    }
}
