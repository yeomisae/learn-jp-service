package com.blue.learnjp.dto;

import java.util.List;

public record QuizAnswerGradeSubmitResponse(
    String problemId,
    String scopeId,
    int gradedCount,
    List<String> summaryLines,
    String status
) {}
