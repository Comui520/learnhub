package com.github.comui520.learnhub.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.knowledge.KnowledgeErrorCode;
import com.github.comui520.learnhub.knowledge.config.rabbitmq.DocumentParseRabbitMqConfig;
import com.github.comui520.learnhub.knowledge.dto.*;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import com.github.comui520.learnhub.knowledge.entity.KnowledgeBase;
import com.github.comui520.learnhub.knowledge.entity.KnowledgeBaseDocument;
import com.github.comui520.learnhub.knowledge.mapper.DocumentFileMapper;
import com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper;
import com.github.comui520.learnhub.knowledge.mapper.KnowledgeBaseDocumentMapper;
import com.github.comui520.learnhub.knowledge.mapper.KnowledgeBaseMapper;
import com.github.comui520.learnhub.knowledge.mq.DocumentParseMessage;
import com.github.comui520.learnhub.knowledge.redis.KnowledgeRedisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class KnowledgeBaseService extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> {

    private static final String EMPTY_FILE_IDS_SENTINEL = "__EMPTY__";
    private static final long CACHE_TTL_SECONDS = 60L;

    private final KnowledgeBaseDocumentMapper knowledgeBaseDocumentMapper;
    private final DocumentFileMapper documentFileMapper;
    private final RabbitTemplate rabbitTemplate;
    private final DocumentTaskMapper documentTaskMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeRedisProperties knowledgeRedisProperties;

    public KnowledgeBaseService(
            KnowledgeBaseDocumentMapper knowledgeBaseDocumentMapper,
            DocumentFileMapper documentFileMapper,
            RabbitTemplate rabbitTemplate,
            DocumentTaskMapper documentTaskMapper,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            KnowledgeRedisProperties knowledgeRedisProperties
    ) {
        this.knowledgeBaseDocumentMapper = knowledgeBaseDocumentMapper;
        this.documentFileMapper = documentFileMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.documentTaskMapper = documentTaskMapper;
        this.redisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.knowledgeRedisProperties = knowledgeRedisProperties;
    }

    public IPage<DocumentTaskResponse> getTaskList(Long userId, Long knowledgeBaseId, Integer page, Integer size, String status) {
        KnowledgeBase kb = findOwned(userId, knowledgeBaseId);
        Page<DocumentTaskResponse> pageSet = new Page<>(page, size);
        return documentTaskMapper.page(pageSet, knowledgeBaseId, status);

    }

    public KnowledgeBaseResponse create(Long userId, CreateKnowledgeBaseRequest request) {
        // 同名检查给友好报错；uk_user_name 是并发兜底
        if (this.exists(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getName, request.name()))) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NAME_EXISTS);
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setUserId(userId);
        kb.setDescription(request.description());
        kb.setCreatedAt(LocalDateTime.now());
        this.save(kb);
        return new KnowledgeBaseResponse(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
    }

    public IPage<KnowledgeBaseResponse> page(Long userId, int page, int size) {
        return this.page(new Page<KnowledgeBase>(page, size),
                        new LambdaQueryWrapper<KnowledgeBase>()
                                .eq(KnowledgeBase::getUserId, userId)
                                .orderByDesc(KnowledgeBase::getCreatedAt))
                .convert(kb -> new KnowledgeBaseResponse(
                        kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt()));
    }

    public KnowledgeBaseResponse getById(Long userId, Long id) {
        String cacheKey = buildDetailCacheKey(userId, id);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, KnowledgeBaseResponse.class);
            } catch (Exception e) {
                log.warn("cache parse failed, fallback to db: key={}", cacheKey, e);
                redisTemplate.delete(cacheKey);
            }
        }
        KnowledgeBase kb = findOwned(userId, id);

        KnowledgeBaseResponse response = new KnowledgeBaseResponse(
                kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());

        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(response),
                    CACHE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("cache set failed: key={}", cacheKey, e);
        }
        return response;
    }

    public KnowledgeBaseResponse update(Long userId, Long id, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase kb = findOwned(userId, id);
        // 改名查重：排除自己
        if (this.exists(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getName, request.name())
                .ne(KnowledgeBase::getId, id))) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NAME_EXISTS);
        }
        kb.setName(request.name());
        kb.setDescription(request.description());
        this.updateById(kb);
        redisTemplate.delete(buildDetailCacheKey(userId, id));
        return new KnowledgeBaseResponse(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
    }

    @Transactional
    public void delete(Long userId, Long id) {
        KnowledgeBase kb = findOwned(userId, id);
        // 有文档先删文档（拒绝级联删除，避免孤儿记录）
        if (knowledgeBaseDocumentMapper.countByKnowledgeBaseId(id) > 0) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_HAS_DOCUMENTS);
        }
        redisTemplate.delete(buildDetailCacheKey(userId, id));
        evictFileIds(id);
        this.removeById(kb.getId());
    }

    public void bindDocuments(Long userId, Long knowledgeBaseId, List<Long> documentIds) {
        // 1. 检查知识库是否存在
        KnowledgeBase kb = findOwned(userId, knowledgeBaseId);
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        List<Long> uploadedFileIds = knowledgeBaseDocumentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeBaseDocument>()
                                .eq(KnowledgeBaseDocument::getKnowledgeBaseId, knowledgeBaseId)
                ).stream()
                .map(KnowledgeBaseDocument::getFileId)
                .toList();
        // 2. 只上传存在的文档
        List<DocumentResponse> documentFiles = documentFileMapper.selectByIdsAndUserId(documentIds, userId, knowledgeBaseId);
        List<KnowledgeBaseDocument> knowledgeBaseDocumentList = documentFiles.stream()
                .map(documentFile -> new KnowledgeBaseDocument(null, kb.getId(), documentFile.fileId(), LocalDateTime.now()))
                .filter(documentFile -> !uploadedFileIds.contains(documentFile.getFileId()))
                .toList();
        knowledgeBaseDocumentMapper.insert(knowledgeBaseDocumentList);
        for (KnowledgeBaseDocument knowledgeBaseDocument : knowledgeBaseDocumentList) {
            sendParseMessage(knowledgeBaseDocument.getFileId());
        }
        evictFileIds(knowledgeBaseId);
    }

    public KnowledgeBase findOwned(Long userId, Long id) {
        KnowledgeBase kb = this.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getUserId, userId)
                .one();
        if (kb == null) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return kb;
    }


    @Transactional
    public void unbindDocuments(Long userId, Long knowledgeBaseId, List<Long> documentDeleteIds) {
        findOwned(userId, knowledgeBaseId);
        if (documentDeleteIds == null || documentDeleteIds.isEmpty()) {
            return;
        }
        Set<Long> deleteSet = Set.copyOf(documentDeleteIds);
        List<Long> retainedFileIds = knowledgeBaseDocumentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeBaseDocument>()
                                .eq(KnowledgeBaseDocument::getKnowledgeBaseId, knowledgeBaseId)
                ).stream()
                .map(KnowledgeBaseDocument::getFileId)
                .filter(id -> !deleteSet.contains(id))
                .toList();
        // 全部删除
        knowledgeBaseDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeBaseDocument>().eq(KnowledgeBaseDocument::getKnowledgeBaseId, knowledgeBaseId));
        // 只恢复保留的关联，不再次发送解析消息。
        if (!retainedFileIds.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            List<KnowledgeBaseDocument> retained = retainedFileIds.stream()
                    .map(fileId -> new KnowledgeBaseDocument(null, knowledgeBaseId, fileId, now))
                    .toList();
            knowledgeBaseDocumentMapper.insert(retained);
        }
        evictFileIds(knowledgeBaseId);
    }

    private void sendParseMessage(Long fileId) {
        try {
            rabbitTemplate.convertAndSend(
                    DocumentParseRabbitMqConfig.EXCHANGE,
                    DocumentParseRabbitMqConfig.ROUTING_KEY,
                    new DocumentParseMessage(fileId)
            );
            log.info("parse message sent: fileId={}", fileId);
        } catch (Exception e) {
            log.error("send parse message failed, will retry later: fileId={}", fileId, e);
        }
    }

    public List<Long> listBoundFileIds(Long userId, Long knowledgeBaseId) {
        findOwned(userId, knowledgeBaseId);

        // 尝试从缓存获取
        String cacheKey = buildFileIdsCacheKey(knowledgeBaseId);
        List<String> cached = redisTemplate.opsForList().range(cacheKey, 0, -1);

        if (cached != null && !cached.isEmpty()) {
            if (cached.size() == 1 && EMPTY_FILE_IDS_SENTINEL.equals(cached.get(0))) {
                return List.of();
            }
            try {
                return cached.stream().map(Long::valueOf).toList();
            } catch (NumberFormatException e) {
                log.warn("file id cache corrupted, fallback to db: key={}", cacheKey, e);
                redisTemplate.delete(cacheKey);
            }
        }

        // 缓存未命中，从数据库获取
        List<Long> fileIds = knowledgeBaseDocumentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeBaseDocument>()
                                .eq(KnowledgeBaseDocument::getKnowledgeBaseId, knowledgeBaseId)
                ).stream()
                .map(KnowledgeBaseDocument::getFileId)
                .toList();
        if (fileIds.isEmpty()) {
            redisTemplate.opsForList().rightPush(cacheKey, EMPTY_FILE_IDS_SENTINEL);
        } else {
            redisTemplate.opsForList().rightPushAll(cacheKey, fileIds.stream().map(String::valueOf).toList());
        }
        redisTemplate.expire(cacheKey, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return fileIds;
    }

    private String buildDetailCacheKey(Long userId, Long knowledgeBaseId) {
        return knowledgeRedisProperties.getKbDetailKey() + userId + ":" + knowledgeBaseId;
    }

    private String buildFileIdsCacheKey(Long knowledgeBaseId) {
        return knowledgeRedisProperties.getKbDfKbKey() + knowledgeBaseId;
    }

    private void evictFileIds(Long knowledgeBaseId) {
        redisTemplate.delete(buildFileIdsCacheKey(knowledgeBaseId));
    }
}
