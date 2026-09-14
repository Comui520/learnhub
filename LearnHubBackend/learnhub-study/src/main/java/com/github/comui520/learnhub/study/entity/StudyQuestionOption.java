package com.github.comui520.learnhub.study.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("study_question_option")
public class StudyQuestionOption {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long questionId;

    private String optionKey;   // A/B/C/D

    private String content;

    private Integer isCorrect;  // 0 或 1
}
