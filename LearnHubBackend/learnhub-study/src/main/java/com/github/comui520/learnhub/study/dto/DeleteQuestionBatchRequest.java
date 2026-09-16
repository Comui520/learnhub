package com.github.comui520.learnhub.study.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "批量删除题目请求")
public record DeleteQuestionBatchRequest (
        @NotNull(message = "ids 不能为空")
        @Schema(description = "题目ID列表",
        example = "1,2,3")
        List<Long> ids
) {
}
