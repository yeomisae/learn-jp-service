package com.blue.learnjp.dto;

public record QuizNextProblemRequest(
    QuizScopeInfo scope,
    String discordSenderId,
    Integer count
) {}
