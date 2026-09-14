package com.github.comui520.learnhub.study.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("study_question")
public class StudyQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long knowledgeBaseId;

    private String questionType;  // 默认 SINGLE_CHOICE

    private String content;       // 题干

    private String analysis;      // 解析，可为 null

    private LocalDateTime createdAt;
}
