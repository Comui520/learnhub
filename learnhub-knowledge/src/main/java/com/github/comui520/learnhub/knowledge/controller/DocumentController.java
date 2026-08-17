package com.github.comui520.learnhub.knowledge.controller;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.knowledge.dto.DocumentResponse;
import com.github.comui520.learnhub.knowledge.service.DocumentService;
import com.github.comui520.learnhub.knowledge.service.impl.DocumentServiceImpl;
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.converters.ResponseSupportConverter;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Documents", description = "文档")
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@SecurityRequirement(name = "bearerAuth")
public class DocumentController {

    private final DocumentService documentService;
    private final CurrentUser currentUser;

    public DocumentController(
            DocumentServiceImpl documentService,
            CurrentUser currentUser
    ) {
        this.documentService = documentService;
        this.currentUser = currentUser;
    }

    @PostMapping(
            value = "/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "上传文档成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（KNOWLEDGE_BASE_ERROR_0404）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "文档已存在（DOCUMENT_ERROR_0409）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务器内部错误（COMMON_0500）")
            }
    )
    @Operation(summary = "上传文档", description = "上传文档到指定知识库")
    public ApiResponse<DocumentResponse> upload(
            @PathVariable(value = "id") Long knowledgeBaseId,
            @RequestParam MultipartFile file
    ) {
        Long userId = currentUser.currentUserId();
        return ApiResponse.success(documentService.upload(knowledgeBaseId, userId, file));
    }
}
