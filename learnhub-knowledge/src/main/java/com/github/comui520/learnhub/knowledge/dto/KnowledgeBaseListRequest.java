package com.github.comui520.learnhub.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "知识库列表请求")
public record KnowledgeBaseListRequest (
        @Min(value = 1, message = "Page number must be greater than or equal to 1")
        @Schema(description = "页码", example = "1")
        Integer page,
        @Min(value = 1, message = "Page size must be greater than or equal to 1")
        @Max(value = 50, message = "Page size must be less than or equal to 50")
        @Schema(description = "页面大小", example = "10")
        Integer size
) {
    public KnowledgeBaseListRequest {
        if (page == null) {
            page = 1;
        }
        if (size == null) {
            size = 10;
        }
    }
}
