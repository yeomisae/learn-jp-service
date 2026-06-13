package com.blue.learnjp.dto;

public record QuizProblemDraftRequest(
    QuizScopeInfo scope,
    String discordSenderId,
    Integer count
) {}
