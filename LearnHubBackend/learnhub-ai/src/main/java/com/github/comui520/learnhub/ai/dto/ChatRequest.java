package com.github.comui520.learnhub.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest (
        @Size(max = 1000)
        @NotBlank
        String question
){
}
