package com.github.comui520.learnhub.ai;

import com.github.comui520.learnhub.common.exception.ErrorCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum AiGenerateError implements ErrorCode {
    JSON_PARSE_FAILED("AI.generate.100", "JSON解析失败", 502),
    USABLE_CONTENT_NOT_FOUND("AI.generate.101", "没有找到可用内容", 409),

    AI_MODEL_RESPONSE_INVALID("AI.generate.102", "AI模型响应无效", 502),

    AI_MODEL_RESPONSE_EMPTY("AI.generate.103", "AI模型响应为空", 502),

    ;
    private final String code;
    private final String message;
    private final Integer httpStatus;

    @Override
    public String code() {
        return this.code;
    }

    @Override
    public String message() {
        return this.message;
    }

    @Override
    public Integer httpStatus() {
        return this.httpStatus;
    }
}
