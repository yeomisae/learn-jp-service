package com.blue.learnjp.dto;

import java.util.List;

public record QuizSessionResponse(
    String scopeId,
    List<String> levels,
    String sessionStatus,
    QuizProblemResponse openProblem,
    String status
) {}
