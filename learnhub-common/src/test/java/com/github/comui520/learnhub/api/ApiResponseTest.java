package com.github.comui520.learnhub.api;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ApiResponseTest {
    @Test
    void shouldCreateSuccessResponseAndKeepGenericData() {
        ApiResponse<List<String>> response = ApiResponse.success(List.of("Java", "Spring"));

        assertThat(response.code()).isEqualTo(CommonErrorCode.SUCCESS.code());
        assertThat(response.message()).isEqualTo(CommonErrorCode.SUCCESS.message());
        assertThat(response.data()).containsExactly("Java", "Spring");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void shouldCreateSuccessResponseWithNoData() {
        ApiResponse<List<String>> response = ApiResponse.success();
        assertThat(response.code()).isEqualTo(CommonErrorCode.SUCCESS.code());
        assertThat(response.data()).isNull();
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void shouldCreateFailureResponseWithoutData() {
        ApiResponse<Void> response = ApiResponse.failure(CommonErrorCode.INVALID_PARAMETER);

        assertThat(response.code()).isEqualTo(CommonErrorCode.INVALID_PARAMETER.code());
        assertThat(response.message()).isEqualTo(CommonErrorCode.INVALID_PARAMETER.message());
        assertThat(response.data()).isNull();
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void shouldCreateFailureResponseWithDetails() {
        ApiResponse<String> response = ApiResponse.failure(
                CommonErrorCode.INVALID_PARAMETER,
                "name 不能为空"
        );

        assertThat(response.data()).isEqualTo("name 不能为空");
        assertThat(response.message()).isEqualTo(CommonErrorCode.INVALID_PARAMETER.message());
    }
}
