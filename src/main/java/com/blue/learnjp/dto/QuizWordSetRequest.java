package com.blue.learnjp.dto;

import java.util.List;

public record QuizWordSetRequest(
    String strategy,
    List<String> levels,
    Integer count,
    List<String> excludeLemmas,
    Boolean requireReading,
    Boolean requireMeaning,
    Boolean requireDictEntry,
    String discordSenderId
) {}
