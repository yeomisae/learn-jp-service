package com.blue.learnjp.dto;

import java.util.List;

public record QuizSessionRequest(
    QuizScopeInfo scope,
    List<String> levels,
    String discordSenderId
) {}
