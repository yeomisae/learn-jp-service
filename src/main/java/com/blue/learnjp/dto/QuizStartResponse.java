package com.blue.learnjp.dto;

import java.util.List;

public record QuizStartResponse(
    String scopeId,
    List<String> levels,
    String sessionStatus,
    QuizProblemResponse openProblem,
    QuizWordSetResponse wordSet,
    String status
) {}
