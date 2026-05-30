package com.blue.learnjp.dto;

import java.util.List;

public record QuizTurnResponse(
    QuizBookmarkUpdateResponse bookmark,
    List<String> targetDisplayLines,
    String mustCopyTargetBlock,
    String mustCopySeparator,
    QuizWordSetResponse wordSet,
    String status
) {}
