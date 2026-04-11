package com.blue.learnjp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "naver.jako")
public record NaverJakoConfig(
    String baseUrl,
    int delayMs,
    int timeoutMs,
    int maxConcurrent
) {}
