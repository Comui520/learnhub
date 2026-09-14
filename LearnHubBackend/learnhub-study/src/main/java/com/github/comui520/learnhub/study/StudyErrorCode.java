package com.github.comui520.learnhub.study;

import com.github.comui520.learnhub.common.exception.ErrorCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum StudyErrorCode implements ErrorCode {

    QUESTION_NOT_FOUND("STUDY_0001", "问题不存在", 404),
    ANSWER_INVALID("STUDY_0002", "答案无效", 400),
    QUESTION_NO_OPTIONS("STUDY_0003", "问题没有选项", 400),
    UNSUPPORTED_OPERATION("STUDY_0004", "不支持的操作", 400),
    QUESTION_NO_CORRECT_OPTION("STUDY_0005", "问题没有正确选项", 400),
    SINGLE_CHOICE_QUESTION_MULTIPLE_CORRECT_OPTIONS("STUDY_0006", "单选问题有多个正确选项", 400);

    private final String code;
    private final String message;
    private final Integer httpStatus;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public Integer httpStatus() {
        return httpStatus;
    }
}
