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
@TableName("study_wrong_question")
public class StudyWrongQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long questionId;

    private Integer wrongCount;     // 默认 1

    private LocalDateTime nextReviewAt;  // 可为 null

    private Integer mastered;       // 0 或 1

    private LocalDateTime updatedAt;
}
