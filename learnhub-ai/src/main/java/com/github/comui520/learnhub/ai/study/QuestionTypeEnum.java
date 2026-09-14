package com.github.comui520.learnhub.ai.study;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum QuestionTypeEnum {
    SINGLE_CHOICE("SINGLE_CHOICE", "单选题"),
    MULTIPLE_CHOICE("MULTIPLE_CHOICE", "多选题");
    ;

    private String code;
    private String type;
}
