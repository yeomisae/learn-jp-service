package com.blue.learnjp.dto;

import java.util.List;

public record QuizAnswerGradeSubmitRequest(
    List<QuizAnswerGradeRequest> grades
) {}
