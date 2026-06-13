package com.blue.learnjp.dto;

import java.util.List;

public record QuizBookmarkUpdateRequest(
    List<String> targetWordIds,
    List<String> wrongWordIds,
    List<String> correctWordIds,
    String discordSenderId
) {}
