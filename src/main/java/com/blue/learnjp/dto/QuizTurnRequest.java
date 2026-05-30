package com.blue.learnjp.dto;

import java.util.List;

public record QuizTurnRequest(
    String strategy,
    List<String> levels,
    Integer count,
    List<String> excludeLemmas,
    Boolean requireReading,
    Boolean requireMeaning,
    Boolean requireDictEntry,
    List<String> targetLemmas,
    List<String> wrongLemmas,
    List<String> correctLemmas
) {}
