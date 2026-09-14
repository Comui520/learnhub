package com.github.comui520.learnhub.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import com.github.comui520.learnhub.infrastructure.minio.MinioProperties;
import com.github.comui520.learnhub.knowledge.DocumentStatus;
import com.github.comui520.learnhub.knowledge.DocumentTaskStatus;
import com.github.comui520.learnhub.knowledge.KnowledgeErrorCode;
import com.github.comui520.learnhub.knowledge.config.rabbitmq.DocumentParseRabbitMqConfig;
import com.github.comui520.learnhub.knowledge.dto.DocumentResponse;
import com.github.comui520.learnhub.knowledge.dto.DocumentRow;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import com.github.comui520.learnhub.knowledge.entity.KnowledgeBaseDocument;
import com.github.comui520.learnhub.knowledge.mapper.DocumentFileMapper;
import com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper;
import com.github.comui520.learnhub.knowledge.mapper.KnowledgeBaseDocumentMapper;
import com.github.comui520.learnhub.knowledge.mq.DocumentParseMessage;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.MinioException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;

/**
 * 文档服务：document_file（文件本体）+ knowledge_base_document（多对多关联）。
 * 对外 API 里的“文档 id”= 关联记录 id。
 */
@Slf4j
@Service
public class DocumentService extends ServiceImpl<KnowledgeBaseDocumentMapper, KnowledgeBaseDocument> {

    private final DocumentFileMapper documentFileMapper;
    private final KnowledgeBaseDocumentMapper knowledgeBaseDocumentMapper;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final RabbitTemplate rabbitTemplate;
    private final DocumentTaskMapper documentTaskMapper;
    private final VectorIndexService vectorIndexService;
    private final KnowledgeBaseService knowledgeBaseService;

    public DocumentService(
            DocumentFileMapper documentFileMapper,
            KnowledgeBaseDocumentMapper knowledgeBaseDocumentMapper,
            MinioClient minioClient,
            MinioProperties minioProperties,
            RabbitTemplate rabbitTemplate,
            DocumentTaskMapper documentTaskMapper,
            VectorIndexService vectorIndexService,

            KnowledgeBaseService knowledgeBaseService) {
        this.documentFileMapper = documentFileMapper;
        this.knowledgeBaseDocumentMapper = knowledgeBaseDocumentMapper;
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.rabbitTemplate = rabbitTemplate;
        this.documentTaskMapper = documentTaskMapper;
        this.vectorIndexService = vectorIndexService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Transactional
    public DocumentResponse upload(Long userId, MultipartFile file) {
        // ② 算 SHA-256（内容指纹）
        String sha256;
        try {
            sha256 = sha256Hex(file.getInputStream());
        } catch (IOException e) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }

        // ④ 文件本体：同一用户同一内容只存一份；已存在则跳过 MinIO 上传
        DocumentFile documentFile = documentFileMapper.findByUserIdAndSha256(userId, sha256);
        if (documentFile == null) {
            try {
                String objectName = "documents/" + userId + "/" + sha256 + "/" + file.getOriginalFilename();
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(objectName)
                        .stream(file.getInputStream(), file.getSize(), -1L)
                        .contentType(file.getContentType())
                        .build());

                documentFile = new DocumentFile();
                documentFile.setUserId(userId);
                documentFile.setSha256(sha256);
                documentFile.setObjectName(objectName);
                documentFile.setFileName(file.getOriginalFilename());
                documentFile.setFileSize(file.getSize());
                documentFile.setContentType(file.getContentType());
                documentFile.setStatus(DocumentStatus.UPLOADED.name());
                documentFile.setCreatedAt(LocalDateTime.now());
                documentFile.setUpdatedAt(LocalDateTime.now());
                documentFileMapper.insert(documentFile);
            } catch (IOException | MinioException e) {
                throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
            }
        }


        return toResponse(documentFile);
    }

    /**
     * 按文档条目 id（关联记录 id）查归属并返回文件本体，无则 404
     */
    public DocumentFile getOwnedFile(Long userId, Long documentId) {
        LambdaQueryWrapper<DocumentFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentFile::getId, documentId)
                .eq(DocumentFile::getUserId, userId);
        DocumentFile document = documentFileMapper.selectOne(wrapper);

        if (document == null) {
            throw new BusinessException(KnowledgeErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    public InputStream downloadContent(DocumentFile file) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(file.getObjectName())
                        .build()
        );
    }

    public String getDownloadUrl(DocumentFile file) throws MinioException {
        log.info("Generating presigned URL for file: {}, object name: {}", file.getId(), file.getObjectName());
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(file.getObjectName())
                        .method(Http.Method.GET)
                        .expiry((int) Duration.ofHours(1).toSeconds())
                        .build()
        );
    }

    // 删除文档
    @Transactional
    public void deleteById(Long userId, Long documentId) {
        getOwnedFile(userId, documentId);

        // ② 没有其他知识库再引用这个文件，才删文件记录 + MinIO 对象
        if (knowledgeBaseDocumentMapper.countByFileId(documentId) == 0) {
            DocumentFile file = documentFileMapper.selectById(documentId);
            if (file == null) {
                return;
            }
            documentFileMapper.deleteById(documentId);
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(file.getObjectName())
                        .build());
                Integer chunkCount = file.getChunkCount();
                vectorIndexService.deleteByFileId(file.getId(), chunkCount);
            } catch (Exception e) {
                // 尽力而为：记录留着让补偿任务处理，删除接口不 500
                log.error("MinIO object delete failed, need compensation: object={}", file.getObjectName(), e);
            }
        } else {
            throw new BusinessException(KnowledgeErrorCode.DOCUMENT_STILL_REFERENCED);
        }
    }

    public IPage<DocumentResponse> pageDocuments(Long userId, Long knowledgeBaseId, long page, long size) {
        return knowledgeBaseDocumentMapper
                .selectPageWithFile(new Page<>(page, size), userId, knowledgeBaseId)
                .convert(this::toResponse);
    }

    private DocumentResponse toResponse(DocumentFile file) {
        return new DocumentResponse(
                file.getId(),
                file.getFileName(),
                file.getFileSize(),
                file.getSha256(),
                file.getStatus(),
                file.getCreatedAt()
        );
    }

    private DocumentResponse toResponse(DocumentRow row) {
        return new DocumentResponse(
                row.getFileId(),
                row.getFileName(),
                row.getFileSize(),
                row.getSha256(),
                row.getStatus(),
                row.getCreatedAt()
        );
    }

    public IPage<DocumentResponse> pageAllDocuments(Long userId, Integer page, Integer size) {
        return documentFileMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<DocumentFile>()
                        .eq(DocumentFile::getUserId, userId)
        ).convert(this::toResponse);
    }


    public void reparse(Long userId, Long documentId) {
        DocumentFile file = getOwnedFile(userId, documentId);
        DocumentTask task = documentTaskMapper.findByFileId(file.getId());
        if (task == null) {
            throw new BusinessException(KnowledgeErrorCode.TASK_NOT_FOUND);
        }
        if (!task.getStatus().equals(DocumentTaskStatus.FAILED.getStatus())) {
            throw new BusinessException(KnowledgeErrorCode.TASK_STATUS_NOT_FAILED);
        }
        task.setStatus(DocumentTaskStatus.PENDING.getStatus());
        task.setUpdatedAt(LocalDateTime.now());
        task.setRetryCount(0);
        documentTaskMapper.updateById(task);
        rabbitTemplate.convertAndSend(
                DocumentParseRabbitMqConfig.EXCHANGE,
                DocumentParseRabbitMqConfig.ROUTING_KEY,
                new DocumentParseMessage(file.getId())
        );
    }
}
