package com.blue.learnjp.dto;

import java.util.List;

public record QuizBookmarkUpdateRequest(
    List<String> targetLemmas,
    List<String> wrongLemmas,
    List<String> correctLemmas
) {}
