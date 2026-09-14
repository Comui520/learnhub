package com.github.comui520.learnhub.study.dto;

import java.util.List;

public record StudyAnswerResponse(
        Long questionId,
        List<String> selectedOptions,
        Boolean correct,
        List<String> correctOptions,
        String analysis
) {}
