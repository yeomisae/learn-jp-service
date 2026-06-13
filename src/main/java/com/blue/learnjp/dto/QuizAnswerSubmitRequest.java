package com.blue.learnjp.dto;

import java.util.List;

public record QuizAnswerSubmitRequest(
    String discordSenderId,
    String displayName,
    String answerText,
    String overallResult,
    String feedback,
    List<QuizAnswerResultRequest> results
) {}
