package com.github.comui520.learnhub.ai.dto;

import java.util.List;

public record GeneratedStudyQuestion(
        String questionType,
        String content,
        String analysis,
        List<GeneratedStudyOption> options
) {
}
