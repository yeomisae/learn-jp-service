package com.blue.learnjp.dto;

import java.util.List;

/**
 * OpenClaw(OpenAI)로부터 받는 형태소 분석 + 패턴 추출 결과.
 */
public record AnalysisResult(
    List<WordInfo> words,
    List<EdgeInfo> edges
) {
    public record WordInfo(
        String surface,
        String lemma,
        String reading,
        String pos,
        String meaning
    ) {}

    public record EdgeInfo(
        String from,
        String to,
        String pattern
    ) {}
}
