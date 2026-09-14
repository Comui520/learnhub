package com.github.comui520.learnhub.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GreetingRequest (
        @Schema(description = "需要问候的名称", example = "LearnHub")
        @NotBlank(message = "Name should not be blank")
        @Size(max=50, message = "Name should not be more than 50 characters")
        String name
){ }
