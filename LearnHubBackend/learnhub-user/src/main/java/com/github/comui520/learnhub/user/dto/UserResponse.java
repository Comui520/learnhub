package com.github.comui520.learnhub.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户信息(脱敏)")
public record UserResponse(
        @Schema(description = "用户ID", example = "1")
        Long id,
        @Schema(description = "用户名", example = "learnhub")
        String username,
        @Schema(description = "昵称", example = "张三")
        String nickname
) {
}
