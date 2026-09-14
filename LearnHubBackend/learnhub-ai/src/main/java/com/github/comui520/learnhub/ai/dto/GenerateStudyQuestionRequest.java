package com.github.comui520.learnhub.ai.dto;

import jakarta.validation.constraints.*;

public record GenerateStudyQuestionRequest(
        @NotNull
        @Min(1)
        @Max(10)
        Integer count,

        @Size(max = 200)
        String topic,

        @NotBlank
        @Pattern(regexp = "SINGLE_CHOICE|MULTIPLE_CHOICE")
        String questionType
) {
}
