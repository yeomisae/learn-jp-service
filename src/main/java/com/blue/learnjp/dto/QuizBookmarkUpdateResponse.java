package com.blue.learnjp.dto;

import java.util.List;
import java.util.Map;

public record QuizBookmarkUpdateResponse(
    int updatedCount,
    Map<String, Integer> appliedDeltas,
    Map<String, String> resolvedMappings,
    List<String> ignoredLemmas,
    List<String> missingLemmas,
    String status
) {}
