package com.blue.learnjp.dto;

public record QuizProblemTarget(
    String wordId,
    String lemma,
    String reading,
    String meaning,
    String source,
    String role
) {}
