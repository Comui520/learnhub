package com.github.comui520.learnhub.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
 * file_id     BIGINT UNSIGNED NOT NULL COMMENT '文件 ID',
 * type        VARCHAR(30)     NOT NULL DEFAULT 'PARSE' COMMENT '任务类型',
 * status      VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
 * retry_count INT             NOT NULL DEFAULT 0 COMMENT '重试次数',
 * last_error  VARCHAR(500)    NULL COMMENT '最近一次错误',
 * created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
 * updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 * PRIMARY KEY (id),
 * KEY idx_file_id (file_id),
 * KEY idx_status (status)
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("document_task")
public class DocumentTask {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fileId;

    private String type;

    private String status;

    private Integer retryCount;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
