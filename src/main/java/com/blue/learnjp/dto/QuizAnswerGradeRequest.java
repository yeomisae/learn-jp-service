package com.blue.learnjp.dto;

import java.util.List;

public record QuizAnswerGradeRequest(
    String answerId,
    String overallResult,
    String feedback,
    List<QuizAnswerResultRequest> results
) {}
