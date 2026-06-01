package com.blue.learnjp.dto;

public record QuizHistoryEntry(
    Long id,
    String occurredAt,
    String wordId,
    String lemma,
    String reading,
    String source,
    String meaning,
    String result,
    Integer bookmark
) {}
