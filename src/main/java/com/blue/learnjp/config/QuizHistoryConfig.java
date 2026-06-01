package com.blue.learnjp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.quiz-history")
public record QuizHistoryConfig(
    String sqlitePath
) {}
