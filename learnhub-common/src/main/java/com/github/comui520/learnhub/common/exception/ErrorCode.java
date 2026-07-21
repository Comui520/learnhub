package com.github.comui520.learnhub.common.exception;

/**
 * 异常接口
 */
public interface ErrorCode {
    String code();
    String message();
    Integer httpStatus();
}
