package com.blue.learnjp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openclaw")
public record OpenClawConfig(
    String baseUrl,
    String apiKey,
    String model
) {}
