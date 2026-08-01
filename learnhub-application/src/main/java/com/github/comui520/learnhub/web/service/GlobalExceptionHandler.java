package com.github.comui520.learnhub.web.service;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import com.github.comui520.learnhub.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        ApiResponse<Void> body = ApiResponse.failure(errorCode);

        log.warn("Business request rejected: code={}", errorCode.code());

        return ResponseEntity
                .status(HttpStatusCode.valueOf(errorCode.httpStatus()))
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldValidationError>>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        List<FieldValidationError> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldValidationError(
                        error.getField(),
                        error.getDefaultMessage()
                ))
                .toList();

        ApiResponse<List<FieldValidationError>> body = ApiResponse.failure(
                CommonErrorCode.INVALID_PARAMETER,
                errors
        );

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(
            HttpMessageNotReadableException exception
    ) {
        ApiResponse<Void> body = ApiResponse.failure(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception
    ) {
        log.error("Unhandled server exception", exception);

        ApiResponse<Void> body = ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR);
        return ResponseEntity.internalServerError().body(body);
    }
}
