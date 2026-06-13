package com.blue.learnjp.dto;

import java.util.List;

public record QuizProblemResponse(
    String id,
    String scopeId,
    String status,
    List<String> levels,
    String sentence,
    String reading,
    String translation,
    List<QuizProblemTarget> targets,
    String createdAt,
    String closedAt
) {}
