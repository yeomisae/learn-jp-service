package com.blue.learnjp.dto;

import java.util.List;

public record QuizOpenAnswerSubmitRequest(
    QuizScopeInfo scope,
    String discordSenderId,
    String displayName,
    String answerText,
    String overallResult,
    String feedback,
    List<QuizAnswerResultRequest> results
) {}
