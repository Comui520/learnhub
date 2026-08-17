package com.github.comui520.learnhub.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.springframework.cglib.core.Local;

import java.time.LocalDateTime;

/**
 * id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
 * knowledge_base_id BIGINT UNSIGNED NOT NULL COMMENT '所属知识库 ID',
 * user_id           BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
 * file_name         VARCHAR(255)    NOT NULL COMMENT '原始文件名',
 * file_size         BIGINT          NOT NULL COMMENT '文件大小（字节）',
 * content_type      VARCHAR(100)    NULL COMMENT 'MIME 类型',
 * sha256            CHAR(64)        NOT NULL COMMENT '文件 SHA-256',
 * object_name       VARCHAR(500)    NOT NULL COMMENT 'MinIO 对象名',
 * status            VARCHAR(20)     NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PARSING/EMBEDDING/COMPLETED/FAILED',
 * created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
 * updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 * PRIMARY KEY (id),
 * UNIQUE KEY uk_user_sha256 (user_id, sha256),
 * KEY idx_knowledge_base_id (knowledge_base_id),
 * KEY idx_status (status)
 */

@Data
@TableName("document")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeBaseId;

    private Long userId;

    private String fileName;

    private Long fileSize;

    private String contentType;

    private String sha256;

    private String objectName;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
