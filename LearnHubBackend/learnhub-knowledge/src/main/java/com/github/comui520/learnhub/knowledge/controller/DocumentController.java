package com.github.comui520.learnhub.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.dto.PageParam;
import com.github.comui520.learnhub.knowledge.dto.DocumentResponse;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import com.github.comui520.learnhub.knowledge.service.DocumentService;
import com.github.comui520.learnhub.user.CurrentUser;
import io.minio.errors.MinioException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Tag(name = "Documents", description = "文档")
@RestController
@RequestMapping("/api/v1/document")
@SecurityRequirement(name = "bearerAuth")
public class DocumentController {

    private final DocumentService documentService;
    private final CurrentUser currentUser;

    public DocumentController(
            DocumentService documentService,
            CurrentUser currentUser
    ) {
        this.documentService = documentService;
        this.currentUser = currentUser;
    }


    /**
     * 上传文档只是单纯的上传, 不和知识库关联
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "上传文档成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务器内部错误（COMMON_0500）")
            }
    )
    @Operation(summary = "上传文档", description = "上传文档, 先不和知识库关联")
    public ApiResponse<DocumentResponse> upload(@RequestParam MultipartFile file) {
        return ApiResponse.success(documentService.upload(currentUser.currentUserId(), file));
    }


    @GetMapping("/{id}/share-url")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "获取下载文档链接成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "文档不存在（DOC_ERROR_0404）")
            }
    )
    @Operation(summary = "分享文档", description = "获取下载文档链接")
    public ApiResponse<String> shareUrl(
            @PathVariable(value = "id") Long documentId
    ) throws MinioException {
        DocumentFile file = documentService.getOwnedFile(currentUser.currentUserId(), documentId);
        return ApiResponse.success(documentService.getDownloadUrl(file));
    }

    @GetMapping("/{id}/download")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "下载成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "文档不存在（DOC_ERROR_0404）")
            }
    )
    @Operation(summary = "下载文档", description = "下载文档")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable(value = "id") Long documentId
    ) throws Exception {
        DocumentFile file = documentService.getOwnedFile(currentUser.currentUserId(), documentId);
        InputStream inputStream = documentService.downloadContent(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
                .body(new InputStreamResource(inputStream));
    }

    @DeleteMapping("/{id}")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "删除文档成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "文档不存在（DOC_ERROR_0404）")
            }
    )
    @Operation(summary = "删除文档", description = "删除文档")
    public ApiResponse<?> delete(
            @PathVariable(value = "id") Long documentId
    ) {
        documentService.deleteById(currentUser.currentUserId(), documentId);
        return ApiResponse.success();
    }

    @GetMapping
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "获取文档列表成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "知识库不存在（KB_ERROR_0404）")
            }
    )
    @Operation(summary = "获取文档列表", description = "获取所有上传的文档列表（分页）")
    public ApiResponse<IPage<DocumentResponse>> get(
            @Valid PageParam request
    ) {
        return ApiResponse.success(
                documentService.pageAllDocuments(currentUser.currentUserId(), request.getPage(), request.getSize())
        );
    }

    @PostMapping("/{id}/reparse")
    @ApiResponses(
            value = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "重试成功"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "文档不存在（DOC_ERROR_0404）"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务器内部错误（COMMON_0500）")
            }
    )
    @Operation(summary = "重试", description = "重试解析文档")
    public ApiResponse<?> reparse(
            @PathVariable(value = "id") Long documentId
    ) {
        documentService.reparse(currentUser.currentUserId(), documentId);
        return ApiResponse.success();
    }
}
