package com.github.comui520.learnhub.study.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "查看问题详情(答案, 解析)")
public record QuestionDetail(
        String questionType,  // 默认 SINGLE_CHOICE

        String content,       // 题干

        String analysis,      // 解析，可为 null

        List<OptionDetail> options  // 选项
) {

}
