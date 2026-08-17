package com.github.comui520.learnhub.knowledge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.knowledge.KnowledgeErrorCode;
import com.github.comui520.learnhub.knowledge.dto.CreateKnowledgeBaseRequest;
import com.github.comui520.learnhub.knowledge.dto.KnowledgeBaseListRequest;
import com.github.comui520.learnhub.knowledge.dto.KnowledgeBaseResponse;
import com.github.comui520.learnhub.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.github.comui520.learnhub.knowledge.entity.KnowledgeBase;
import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import com.github.comui520.learnhub.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;


@Tag(name = "Knowledge Bases", description = "知识库")
@RequestMapping("/api/v1/knowledge-bases")
@RestController
@SecurityRequirement(name = "bearerAuth")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    private final CurrentUser currentUser;
    
    public KnowledgeBaseController(
            KnowledgeBaseServiceImpl knowledgeBaseService,
            CurrentUser currentUser
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
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
        Long userId = currentUser.currentUserId();
        return ApiResponse.success(knowledgeBaseService.create(userId, request));
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
        int page = request.page();
        int size = request.size();
        Long userId = currentUser.currentUserId();
        return ApiResponse.success(knowledgeBaseService.page(userId, page, size));
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
        Long userId = currentUser.currentUserId();
        KnowledgeBase knowledgeBase = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getUserId, userId)
                .one();
        if (knowledgeBase == null) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return ApiResponse.success(new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getCreatedAt()
        ));
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
    ){
        Long userId = currentUser.currentUserId();
        KnowledgeBase knowledgeBase = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getUserId, userId)
                .one();
        if (knowledgeBase == null) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        knowledgeBase.setName(request.name());
        knowledgeBase.setDescription(request.description());
        knowledgeBaseService.updateById(knowledgeBase);
        return ApiResponse.success(new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getCreatedAt()
        ));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long userId = currentUser.currentUserId();
        knowledgeBaseService.delete(userId, id);
        return ApiResponse.success(null);
    }
}
