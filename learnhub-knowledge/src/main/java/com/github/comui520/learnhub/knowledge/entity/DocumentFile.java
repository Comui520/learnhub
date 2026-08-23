package com.github.comui520.learnhub.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件本体：同一用户同一内容（sha256）只存一份。
 * 解析状态机也挂在文件上（同一个文件不管进几个知识库，解析结果一致）。
 */
@Data
@TableName("document_file")
public class DocumentFile {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String sha256;

    private String objectName;

    private String fileName;

    private Long fileSize;

    private String contentType;

    private String status;

    private Integer chunkCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
