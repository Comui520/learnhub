package com.github.comui520.learnhub.study.dto;

import com.github.comui520.learnhub.common.dto.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Question View Page Request")
public class QuestionViewPageRequest extends PageParam {
    @Schema(description = "Knowledge Base ID")
    @NotNull
    Long knowledgeBaseId;
    @Schema(description = "Question Type")
    String questionType; // Single Multiple
}
