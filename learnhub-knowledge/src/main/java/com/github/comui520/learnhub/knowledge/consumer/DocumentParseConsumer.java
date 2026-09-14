package com.github.comui520.learnhub.knowledge.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.comui520.learnhub.infrastructure.minio.MinioProperties;
import com.github.comui520.learnhub.knowledge.DocumentStatus;
import com.github.comui520.learnhub.knowledge.DocumentTaskStatus;
import com.github.comui520.learnhub.knowledge.config.rabbitmq.DocumentParseRabbitMqConfig;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import com.github.comui520.learnhub.knowledge.mapper.DocumentFileMapper;
import com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper;
import com.github.comui520.learnhub.knowledge.mq.DocumentParseMessage;
import com.github.comui520.learnhub.knowledge.service.DocumentParseService;
import com.github.comui520.learnhub.knowledge.service.VectorIndexService;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class DocumentParseConsumer {

    private final DocumentFileMapper documentFileMapper;
    private final DocumentTaskMapper documentTaskMapper;
    private final DocumentParseService documentParseService;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final VectorIndexService vectorIndexService;

    public DocumentParseConsumer(
            DocumentFileMapper documentFileMapper,
            DocumentTaskMapper documentTaskMapper,
            DocumentParseService documentParseService,
            MinioProperties minioProperties,
            MinioClient minioClient,
            VectorIndexService vectorIndexService
    ) {
        this.documentFileMapper = documentFileMapper;
        this.documentTaskMapper = documentTaskMapper;
        this.minioProperties = minioProperties;
        this.documentParseService = documentParseService;
        this.minioClient = minioClient;
        this.vectorIndexService = vectorIndexService;
    }

    @RabbitListener(
            queues = DocumentParseRabbitMqConfig.PARSE_QUEUE,
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void onParse(DocumentParseMessage message) {

        Long fileId = message.fileId();
        DocumentTask task = documentTaskMapper.findByFileId(fileId);

        if (task != null && task.getStatus().equals(DocumentTaskStatus.SUCCESS.getStatus())) {
            return;
        }

        if (task == null) {
            task = new DocumentTask(
                    null,
                    fileId,
                    "PARSE",
                    DocumentTaskStatus.PENDING.getStatus(),
                    0,
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now()
            );
            documentTaskMapper.insert(task);
        }
        // 更新document状态
        documentFileMapper.update(null, new LambdaUpdateWrapper<DocumentFile>()
                .eq(DocumentFile::getId, fileId)
                .set(DocumentFile::getStatus, DocumentStatus.PARSING.getStatus()));

        // 更新task状态
        task.setStatus(DocumentTaskStatus.RUNNING.getStatus());
        documentTaskMapper.updateById(task);

        try {
            List<Document> chunks = parseContent(fileId);
            vectorIndexService.addChunks(chunks);
            documentFileMapper.update(null, new LambdaUpdateWrapper<DocumentFile>()
                    .eq(DocumentFile::getId, fileId)
                    .set(DocumentFile::getStatus, DocumentStatus.COMPLETED.getStatus()));

            task.setStatus(DocumentTaskStatus.SUCCESS.getStatus());
            // Clear stale error details after a successful retry.
            task.setLastError(null);
            task.setUpdatedAt(LocalDateTime.now());
            documentTaskMapper.updateById(task);

        } catch (Exception e) {

            documentFileMapper.update(null, new LambdaUpdateWrapper<DocumentFile>()
                    .eq(DocumentFile::getId, fileId)
                    .set(DocumentFile::getStatus, DocumentStatus.FAILED.getStatus()));

            task.setStatus(DocumentTaskStatus.FAILED.getStatus());
            task.setLastError(e.getMessage());
            task.setRetryCount(task.getRetryCount() + 1);
            task.setUpdatedAt(LocalDateTime.now());
            documentTaskMapper.updateById(task);

            log.error("parse document failed: fileId={}", fileId, e);

            throw new RuntimeException(e);
        }

    }


    private List<Document> parseContent(Long fileId) throws Exception {
        DocumentFile file = documentFileMapper.selectById(fileId);
        if (file == null) {
            throw new IllegalStateException("File not found: " + fileId);
        }
        InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(file.getObjectName())
                        .build()
        );

        return documentParseService.parseAndChunk(
                fileId,
                file.getUserId(),
                file.getFileName(),
                inputStream
        );
    }
}
