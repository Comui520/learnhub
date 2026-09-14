package com.github.comui520.learnhub.study.controller;

import com.github.comui520.learnhub.ai.dto.GenerateStudyQuestionRequest;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.study.dto.StudyAnswerResponse;
import com.github.comui520.learnhub.study.dto.StudyQuestionResponse;
import com.github.comui520.learnhub.study.dto.StudyQuestionView;
import com.github.comui520.learnhub.study.dto.SubmitStudyAnswerRequest;
import com.github.comui520.learnhub.study.service.StudyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Study", description = "学习与答题")
@RestController
@RequestMapping("/api/v1/study")
@SecurityRequirement(name = "bearerAuth")
public class StudyController {

    private final StudyService studyService;

    public StudyController(StudyService studyService) {
        this.studyService = studyService;
    }

    @GetMapping("/questions/{questionId}")
    @Operation(summary = "获取题目", description = "获取当前登录用户拥有的题目，不返回正确答案")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "获取成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "题目不存在（STUDY_0001）")
    })
    public ApiResponse<StudyQuestionView> getQuestion(@PathVariable Long questionId) {
        return ApiResponse.success(studyService.getQuestionView(questionId));
    }

    @PostMapping("/questions/{questionId}/attempts")
    @Operation(summary = "提交答案", description = "提交选项编码，例如单选 [B]，多选 [A, C]")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "判题完成"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "答案格式或选项无效（STUDY_0002）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "题目不存在（STUDY_0001）")
    })
    public ApiResponse<StudyAnswerResponse> submitAnswer(
            @PathVariable Long questionId,
            @Valid @RequestBody SubmitStudyAnswerRequest request
    ) {
        return ApiResponse.success(studyService.submitAnswer(questionId, request.options()));
    }

    @Operation(summary = "生成题目", description = "根据知识库生成题目，返回题目列表")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "生成成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（STUDY_0001）")
    })
    @PostMapping("/question/{knowledgeBaseId}/generate")
    public ApiResponse<List<StudyQuestionView>> generateQuestion(
            @Valid @RequestBody GenerateStudyQuestionRequest request,
            @PathVariable Long knowledgeBaseId
    ) {
        return ApiResponse.success(studyService.generateQuestion(request, knowledgeBaseId));
    }
}
