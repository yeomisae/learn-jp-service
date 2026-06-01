package com.blue.learnjp.dto;

import java.util.List;

public record QuizHistoryDailyResponse(
    String date,
    String zone,
    int correctCount,
    int wrongCount,
    List<QuizHistoryEntry> correctWords,
    List<QuizHistoryEntry> wrongWords,
    List<String> correctLines,
    List<String> wrongLines,
    String mustCopyReport
) {}
