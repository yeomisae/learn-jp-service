package com.blue.learnjp.dto;

public record QuizPendingAnswerResponse(
    String answerId,
    long userId,
    String displayName,
    String answerText,
    String createdAt
) {}
