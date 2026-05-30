package com.blue.learnjp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.example-queue")
public record ExampleQueueConfig(
    long consumeDelayMs,
    int batchSize,
    RetryConfig retry
) {

    public ExampleQueueConfig {
        consumeDelayMs = consumeDelayMs > 0 ? consumeDelayMs : 2_000;
        batchSize = batchSize > 0 ? batchSize : 1;
        retry = retry != null ? retry : RetryConfig.defaults(8, 10_000, 300_000, 2.0);
    }
}
