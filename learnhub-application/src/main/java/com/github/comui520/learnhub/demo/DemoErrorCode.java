package com.github.comui520.learnhub.demo;

import com.github.comui520.learnhub.common.exception.ErrorCode;

public enum DemoErrorCode implements ErrorCode {
    NAME_FORBIDDEN("DEMO_ERROR_0422", "Name is forbidden", 422),
    ;
    private final String code;
    private final String message;
    private final Integer httpStatus;

    DemoErrorCode(String code, String message, Integer httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

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
