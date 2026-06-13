package com.blue.learnjp.dto;

import java.util.List;

public record QuizProblemCreateRequest(
    QuizScopeInfo scope,
    String discordSenderId,
    String sentence,
    String reading,
    String translation,
    List<QuizProblemTarget> targets
) {}
