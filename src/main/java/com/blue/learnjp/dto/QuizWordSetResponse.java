package com.blue.learnjp.dto;

import java.util.List;

public record QuizWordSetResponse(
    String strategyUsed,
    int requestedCount,
    int returnedCount,
    List<QuizWord> words
) {
    public record QuizWord(
        String lemma,
        String reading,
        String meaning,
        String pos,
        String posDetail,
        String posDesc,
        String source,
        int starGrade,
        String dictEntryId
    ) {}
}
