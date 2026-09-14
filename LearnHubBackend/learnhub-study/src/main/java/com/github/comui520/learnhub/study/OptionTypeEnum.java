package com.github.comui520.learnhub.study;

import lombok.Getter;

@Getter
public enum OptionTypeEnum {
    SINGLE_CHOICE("SINGLE_CHOICE", "单选题"),
    MULTIPLE_CHOICE("MULTIPLE_CHOICE", "多选题");

    private final String code;
    private final String type;

    OptionTypeEnum(String code, String type) {
        this.code = code;
        this.type = type;
    }
}
