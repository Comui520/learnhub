package com.github.comui520.learnhub.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
 * user_id     BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
 * name        VARCHAR(50)     NOT NULL COMMENT '知识库名称',
 * description VARCHAR(255)    NULL COMMENT '描述',
 * created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
 * updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 * PRIMARY KEY (id),
 * UNIQUE KEY uk_user_name (user_id, name),
 * KEY idx_user_id (user_id)
 */

@Data
@TableName("`knowledge_base`")
public class KnowledgeBase {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
