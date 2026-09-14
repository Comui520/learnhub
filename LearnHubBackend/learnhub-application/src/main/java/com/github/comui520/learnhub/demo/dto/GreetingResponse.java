package com.github.comui520.learnhub.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record GreetingResponse(
        @Schema(description = "生成的问候语", example = "Hello, LearnHub!")
        String greeting
) {
}
