package com.blue.learnjp.dto;

import java.util.List;

public record QuizNextProblemResponse(
    QuizProblemResponse closedProblem,
    List<String> summaryLines,
    QuizProblemDraftResponse nextDraft,
    String status
) {}
