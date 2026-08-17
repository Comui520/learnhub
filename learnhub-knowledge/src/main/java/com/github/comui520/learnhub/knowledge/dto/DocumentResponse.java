package com.github.comui520.learnhub.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Schema(description = "文档响应")
public record DocumentResponse (
        @Schema(description = "文档ID", example = "1")
        Long id,
        @Schema(description = "知识库ID", example = "1")
        Long knowledgeBaseId,
        @Schema(description = "文件名", example = "example.txt")
        String fileName,
        @Schema(description = "文件大小", example = "1024")
        Long fileSize,
        @Schema(description = "文件类型", example = "text/plain")
        String sha256,
        @Schema(description = "文件状态", example = "uploaded")
        String status,
        @Schema(description = "创建时间", example = "2024-06-01T12:00:00")
        LocalDateTime createdAt
){
}
