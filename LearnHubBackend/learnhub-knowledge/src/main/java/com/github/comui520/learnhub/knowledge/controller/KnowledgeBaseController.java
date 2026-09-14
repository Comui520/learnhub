package com.github.comui520.learnhub.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.dto.PageParam;
import com.github.comui520.learnhub.knowledge.dto.*;
import com.github.comui520.learnhub.knowledge.service.DocumentService;
import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.simpleframework.xml.core.Validate;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Knowledge Bases", description = "知识库")
@RequestMapping("/api/v1/knowledge-bases")
@RestController
@SecurityRequirement(name = "bearerAuth")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final CurrentUser currentUser;
    private final DocumentService documentService;

    public KnowledgeBaseController(
            KnowledgeBaseService knowledgeBaseService,
            DocumentService documentService,
            CurrentUser currentUser
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
        this.currentUser = currentUser;
    }

    @PostMapping("/create")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "创建知识库成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "知识库名称已存在（KNOWLEDGE_BASE_ERROR_0409）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务器内部错误（COMMON_0500）")
    })
    @Operation(summary = "创建知识库", description = "创建新的知识库")
    public ApiResponse<KnowledgeBaseResponse> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeBaseService.create(currentUser.currentUserId(), request));
    }

    @GetMapping
    @Operation(summary = "获取知识库列表", description = "获取当前用户的知识库列表，支持分页")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "获取知识库列表成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）")
    })
    public ApiResponse<IPage<KnowledgeBaseResponse>> list(
            @Valid KnowledgeBaseListRequest request
    ) {
        return ApiResponse.success(knowledgeBaseService.page(
                currentUser.currentUserId(), request.page(), request.size()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取知识库详情", description = "获取指定知识库的详情")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "获取知识库详情成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（KNOWLEDGE_BASE_ERROR_0404）")
            }
    )
    public ApiResponse<KnowledgeBaseResponse> get(@PathVariable Long id) {
        return ApiResponse.success(knowledgeBaseService.getById(currentUser.currentUserId(), id));
    }

    @PutMapping("/{id}")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新知识库成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（KNOWLEDGE_BASE_ERROR_0404）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "知识库名称已存在（KNOWLEDGE_BASE_ERROR_0409）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务器内部错误（COMMON_0500）")
            }
    )
    @Operation(summary = "更新知识库", description = "更新指定知识库的名称和描述")
    public ApiResponse<KnowledgeBaseResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeBaseService.update(currentUser.currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除知识库", description = "删除指定知识库（有文档时返回 409）")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "删除知识库成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（KNOWLEDGE_BASE_ERROR_0404）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "知识库下有文档（KB_ERROR_0409）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务器内部错误（COMMON_0500）")
            }
    )
    public ApiResponse<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(currentUser.currentUserId(), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/bind-document")
    @Operation(summary = "绑定文档到知识库", description = "将文档绑定到指定知识库")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "绑定文档到知识库成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（KNOWLEDGE_BASE_ERROR_0404）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务器内部错误（COMMON_0500）")
            }
    )
    public ApiResponse<Void> bindDocument(
            @Valid @RequestBody BindDocumentRequest request
    ) {
        knowledgeBaseService.bindDocuments(currentUser.currentUserId(), request.knowledgeBaseId(), request.documentFileIds());
        return ApiResponse.success();
    }

    @GetMapping("/{id}/file")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "获取文档列表成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（KB_ERROR_0404）")
            }
    )
    @Operation(summary = "获取文档列表", description = "获取知识库下的文档列表（分页）")
    public ApiResponse<IPage<DocumentResponse>> get(
            @PathVariable Long id,
            @Valid PageParam request
    ) {
        return ApiResponse.success(
                documentService.pageDocuments(currentUser.currentUserId(), id, request.getPage(), request.getSize())
        );
    }

    @PostMapping("/unbind-document")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "解绑文档成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（KNOWLEDGE_BASE_ERROR_0404）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务器内部错误（COMMON_0500）")
            }
    )
    @Operation(summary = "解绑文档", description = "将文档从知识库中解绑, 其实就是: 全部解绑, 然后重新绑定")
    public ApiResponse<Void> unbindDocument(
            @Valid @RequestBody BindDocumentRequest request
    ) {
        knowledgeBaseService.unbindDocuments(currentUser.currentUserId(), request.knowledgeBaseId(), request.documentFileIds());
        return ApiResponse.success();
    }

    @GetMapping("/{id}/task")
    @Operation(summary = "获取文档解析任务", description = "获取文档解析任务")
    public ApiResponse<IPage<DocumentTaskResponse>> getTaskList(
            @PathVariable(value = "id") Long knowledgeBaseId,
            @RequestParam(required = false) String status,
            @Valid PageParam pageParam
    ){
        return ApiResponse.success(
                knowledgeBaseService.getTaskList(currentUser.currentUserId(), knowledgeBaseId, pageParam.getPage(), pageParam.getSize(), status)
        );
    }
}
