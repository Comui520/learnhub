package com.github.comui520.learnhub.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库 ↔ 文件 的多对多关联。
 * 删除“库里的文档” = 删这里的一行；文件本体在没有任何关联引用时才删除。
 */
@Data
@AllArgsConstructor
@TableName("knowledge_base_document")
public class KnowledgeBaseDocument {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeBaseId;

    private Long fileId;

    private LocalDateTime createdAt;
}
