package com.blue.learnjp.dto;

public record QuizHistoryEntry(
    Long id,
    String occurredAt,
    Long userId,
    String userName,
    String wordId,
    String lemma,
    String reading,
    String source,
    String meaning,
    String result,
    Integer bookmark
) {}
