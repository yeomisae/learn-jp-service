package com.blue.learnjp.dto;

import java.util.List;

public record QuizStartRequest(
    QuizScopeInfo scope,
    String discordSenderId,
    List<String> levels,
    Integer count
) {}
