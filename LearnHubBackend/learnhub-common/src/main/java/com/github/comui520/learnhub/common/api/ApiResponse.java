package com.github.comui520.learnhub.common.api;

import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import com.github.comui520.learnhub.common.exception.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * API响应结果类
 */

public record ApiResponse<T>(
        String code,
        String message,
        Integer httpStatus,
        T data,
        Instant timestamp
) {
    public ApiResponse {
        Objects.requireNonNull(code, "Code must not be null");
        Objects.requireNonNull(message, "Message must not be null");
        Objects.requireNonNull(timestamp, "Timestamp must not be null");
        Objects.requireNonNull(httpStatus, "Http status must not be null");
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<T>(
                CommonErrorCode.SUCCESS.code(),
                CommonErrorCode.SUCCESS.message(),
                CommonErrorCode.SUCCESS.httpStatus(),
                data,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> success(){
        return new ApiResponse<T>(
                CommonErrorCode.SUCCESS.code(),
                CommonErrorCode.SUCCESS.message(),
                CommonErrorCode.SUCCESS.httpStatus(),
                null,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, T data) {
        return new ApiResponse<T>(
                errorCode.code(),
                errorCode.message(),
                errorCode.httpStatus(),
                data,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode) {
        return failure(errorCode, null);
    }
}
