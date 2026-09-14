package com.github.comui520.learnhub.study.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SubmitStudyAnswerRequest(
        @NotEmpty(message = "Options must not be empty")
        @Size(max = 4, message = "At most four options are allowed")
        List<@NotBlank(message = "Option must not be blank")
                @Size(max = 1, message = "Option must be one character") String> options
) {}
