package com.blue.learnjp.dto;

import java.util.List;

public record ImportRequest(
    String sentence,
    List<AnalysisResult.WordInfo> words,
    List<AnalysisResult.EdgeInfo> edges
) {}
