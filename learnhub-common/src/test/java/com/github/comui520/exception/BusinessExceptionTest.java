package com.github.comui520.exception;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BusinessExceptionTest {
    @Test
    void shouldKeepErrorCodeAndMessage() {
        BusinessException exception = new BusinessException(CommonErrorCode.INVALID_PARAMETER);

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER);
        assertThat(exception.getMessage()).isEqualTo(CommonErrorCode.INVALID_PARAMETER.message());
    }

    @Test
    void shouldRejectNullErrorCode() {
        assertThatThrownBy(() -> new BusinessException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errorCode must not be null");
    }
}
