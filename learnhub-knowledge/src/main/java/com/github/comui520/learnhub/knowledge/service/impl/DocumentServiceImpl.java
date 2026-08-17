package com.github.comui520.learnhub.knowledge.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import com.github.comui520.learnhub.infrastructure.minio.MinioProperties;
import com.github.comui520.learnhub.knowledge.DocumentStatus;
import com.github.comui520.learnhub.knowledge.KnowledgeErrorCode;
import com.github.comui520.learnhub.knowledge.dto.DocumentResponse;
import com.github.comui520.learnhub.knowledge.entity.Document;
import com.github.comui520.learnhub.knowledge.entity.KnowledgeBase;
import com.github.comui520.learnhub.knowledge.mapper.DocumentMapper;
import com.github.comui520.learnhub.knowledge.mapper.KnowledgeBaseMapper;
import com.github.comui520.learnhub.knowledge.service.DocumentService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.MinioException;
import org.bouncycastle.util.Exceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;

@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements DocumentService {
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public DocumentServiceImpl(
            DocumentMapper documentMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            MinioClient minioClient,
            MinioProperties minioProperties
    ) {
        this.documentMapper = documentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Transactional
    @Override
    public DocumentResponse upload(Long userId, Long knowledgeBaseId, MultipartFile file) {
        KnowledgeBase kb = knowledgeBaseMapper.findByIdAndUserId(knowledgeBaseId, userId);
        if (kb == null) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        try {
            String sha256Hex = sha256Hex(file.getInputStream());
            if (documentMapper.findByKnowledgeBaseIdAndSha256(knowledgeBaseId, sha256Hex) != null) {
                throw new BusinessException(KnowledgeErrorCode.DUPLICATE_DOCUMENT);
            }
            String objectName = "documents/" + userId + "/" + sha256Hex + "/" + file.getOriginalFilename();
            minioClient.putObject(PutObjectArgs.builder()
                            .bucket(minioProperties.getBucket())
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1L)
                            .contentType(file.getContentType())
                    .build());
            Document document = new Document();
            document.setContentType(file.getContentType());
            document.setFileName(file.getOriginalFilename());
            document.setKnowledgeBaseId(knowledgeBaseId);
            document.setSha256(sha256Hex);
            document.setFileSize(file.getSize());
            document.setObjectName(objectName);
            document.setStatus(DocumentStatus.UPLOADED.name());
            document.setUserId(userId);
            this.save(document);
            return new DocumentResponse(
                    document.getId(),
                    document.getKnowledgeBaseId(),
                    document.getFileName(),
                    document.getFileSize(),
                    document.getSha256(),
                    document.getStatus(),
                    document.getCreatedAt()
            );
        } catch (IOException | MinioException e) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }
    }
}
