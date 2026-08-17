package com.github.comui520.learnhub.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "知识库响应")
public record KnowledgeBaseResponse(
        @Schema(description = "知识库ID", example = "1")
        Long id,
        @Schema(description = "知识库名称", example = "Java基础知识库")
        String name,
        @Schema(description = "知识库描述", example = "这是一个关于Java基础知识的库")
        String description,
        @Schema(description = "创建时间", example = "2023-01-01T00:00:00")
        LocalDateTime createdAt
) {
}
