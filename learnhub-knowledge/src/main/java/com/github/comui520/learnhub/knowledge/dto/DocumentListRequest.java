package com.github.comui520.learnhub.knowledge.dto;

import com.github.comui520.learnhub.common.dto.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Schema(name = "DocumentListRequest", description = "文档列表请求参数")
public class DocumentListRequest extends PageParam {
    @Schema(description = "知识库ID", example = "1")
    Long id;
}
