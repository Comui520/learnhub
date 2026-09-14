package com.github.comui520.learnhub.user;

import com.github.comui520.learnhub.common.exception.ErrorCode;

public enum UserErrorCode implements ErrorCode {
    INVALID_CREDENTIALS("USER_ERROR_0401", "Invalid username or password", 401),
    ACCOUNT_DISABLED("USER_ERROR_0403", "Account is disabled", 403),
    USER_NOT_FOUND("USER_ERROR_0404", "User not found", 404),
    USERNAME_ALREADY_EXISTS("USER_ERROR_0409", "Username already exists", 409),
    ACCOUNT_LOCKED("COMMON_0423", "Account is temporarily locked", 423);
    ;
    private final String code;
    private final String message;
    private final Integer httpStatus;

    UserErrorCode(String code, String message, Integer httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

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
