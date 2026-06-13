package com.blue.learnjp.dto;

import java.util.List;

public record QuizProblemDraftResponse(
    String scopeId,
    List<String> levels,
    QuizWordSetResponse wordSet,
    String status
) {}
