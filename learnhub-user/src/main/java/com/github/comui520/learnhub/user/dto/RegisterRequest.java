package com.github.comui520.learnhub.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "注册请求")
public record RegisterRequest(
        @Schema(description = "登录名", example = "learnhub")
        @NotBlank(message = "Username should not be blank")
        @Size(min = 3, max = 30, message = "Username length should be between 3 and 30")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username should only contain letters, digits or underscore")
        String username,

        @Schema(description = "密码", example = "password123")
        @NotBlank(message = "Password should not be blank")
        @Size(min = 8, max = 64, message = "Password length should be between 8 and 64")
        String password
) {
}
