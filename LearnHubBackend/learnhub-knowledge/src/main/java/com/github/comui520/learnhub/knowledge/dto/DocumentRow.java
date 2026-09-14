package com.github.comui520.learnhub.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * XML 多表查询的中间结果（MyBatis 映射需要 setter，所以用 @Data 类而不是 record）。
 */
@Data
public class DocumentRow {
    private Long id;
    private Long fileId;
    private Long knowledgeBaseId;
    private String fileName;
    private Long fileSize;
    private String sha256;
    private String status;
    private LocalDateTime createdAt;
}
