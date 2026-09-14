package com.github.comui520.learnhub.common.exception;

public enum CommonErrorCode implements ErrorCode {
    SUCCESS("COMMON_0000", "SUCCESS", 200),
    INVALID_PARAMETER("COMMON_0400", "INVALID_PARAMETER", 400),
    INTERNAL_ERROR("COMMON_0500", "INTERNAL_ERROR", 500),
    UNAUTHENTICATED("COMMON_0401", "UNAUTHENTICATED", 401),
    FORBIDDEN("COMMON_0403", "FORBIDDEN", 403),
    ;

    private final String code;
    private final String message;
    private final Integer httpStatus;

    CommonErrorCode(String code, String message, Integer httpStatus) {
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
