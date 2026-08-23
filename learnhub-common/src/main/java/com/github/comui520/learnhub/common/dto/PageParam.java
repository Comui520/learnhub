package com.github.comui520.learnhub.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "分页请求")
public class PageParam {
    @Min(value = 1, message = "Page number must be greater than or equal to 1")
    @Schema(description = "页码", example = "1")
    Integer page = 1;
    @Schema(description = "页大小", example = "10")
    @Min(value = 1, message = "Page size must be greater than or equal to 1")
    @Max(value = 50, message = "Page size must be less than or equal to 50")
    Integer size = 10;
}
