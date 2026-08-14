package com.github.comui520.learnhub.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(description = "访问令牌")
        String token,

        @Schema(description = "令牌 类型", example = "Bearer")
        String tokenType,

        @Schema(description = "令牌 过期时间（秒）")
        Long expirationSeconds
) {
}
