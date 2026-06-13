package com.blue.learnjp.dto;

public record QuizSessionEndRequest(
    QuizScopeInfo scope,
    String discordSenderId
) {}
