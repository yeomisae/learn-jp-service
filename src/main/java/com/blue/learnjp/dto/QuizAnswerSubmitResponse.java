package com.blue.learnjp.dto;

import java.util.List;

public record QuizAnswerSubmitResponse(
    String answerId,
    String problemId,
    String scopeId,
    long userId,
    QuizBookmarkUpdateResponse bookmark,
    List<String> targetDisplayLines,
    String status
) {}
