package com.github.comui520.learnhub.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "绑定文档请求")
public record BindDocumentRequest (
        @NotEmpty(message = "documentFileIds must not be empty")
        @Schema(description = "文档文件ID列表", example = "[1, 2]")
        List<Long> documentFileIds,
        @NotNull(message = "knowledgeBaseId must not be null")
        @Schema(description = "知识库ID", example = "1")
        Long knowledgeBaseId
) {
}
