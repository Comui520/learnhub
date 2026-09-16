package com.github.comui520.learnhub.study.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 题目列表视图。
 *
 * <p>这里不能使用 record：题目列表 XML 使用 nested resultMap 把多行 join 结果
 * 聚合到 options 集合，MyBatis 需要通过无参构造器和 setter 回填对象。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "StudyQuestionView")
public class StudyQuestionView {

    @Schema(description = "问题ID")
    private Long id;

    @Schema(description = "知识库ID")
    private Long knowledgeBaseId;

    @Schema(description = "问题类型")
    private String questionType;

    @Schema(description = "题干")
    private String content;

    @Schema(description = "选项")
    private List<StudyOptionResponse> options = new ArrayList<>();
}
