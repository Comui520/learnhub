package com.github.comui520.learnhub.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "登录请求")
public record LoginRequest(
        @Schema(description = "用户名", example = "learnhub")
        @NotBlank
        @Size(max = 30, message = "Username should not be longer than 30")
        String username,

        @Schema(description = "密码", example = "password123")
        @NotBlank
        @Size(max = 64, message = "Password should not be longer than 64")
        String password
) {
}
