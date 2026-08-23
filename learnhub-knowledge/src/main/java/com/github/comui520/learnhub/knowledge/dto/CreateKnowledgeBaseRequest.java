package com.github.comui520.learnhub.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "Create Knowledge Base Request")
public record CreateKnowledgeBaseRequest(
        @Size(max = 50)
        @NotBlank
        @Schema(description = "知识库名称", example = "My Knowledge Base")
        String name,
        @Size(max = 1000)
        @Schema(description = "知识库描述", example = "This is my knowledge base")
        String description
) {
}
