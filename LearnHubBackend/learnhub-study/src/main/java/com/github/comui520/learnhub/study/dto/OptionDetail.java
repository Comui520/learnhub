package com.github.comui520.learnhub.study.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "带正确与否的选项详情")
public record OptionDetail(
        @Schema(description = "选项Key，例如 A/B/C/D")
        String optionKey,   // A/B/C/D

        @Schema(description = "选项内容")
        String content,

        @Schema(description = "是否正确")
        boolean isCorrect  // 0 或 1
) {
}
