package com.github.comui520.learnhub.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.dto.PageParam;
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
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeBaseService extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> {

    private final KnowledgeBaseDocumentMapper knowledgeBaseDocumentMapper;
    private final DocumentFileMapper documentFileMapper;
    private final RabbitTemplate rabbitTemplate;
    private final CurrentUser currentUser;
    private final DocumentTaskMapper documentTaskMapper;

    public KnowledgeBaseService(
            KnowledgeBaseDocumentMapper knowledgeBaseDocumentMapper,
            DocumentFileMapper documentFileMapper,
            RabbitTemplate rabbitTemplate,
            CurrentUser currentUser, DocumentTaskMapper documentTaskMapper) {
        this.knowledgeBaseDocumentMapper = knowledgeBaseDocumentMapper;
        this.documentFileMapper = documentFileMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.currentUser = currentUser;
        this.documentTaskMapper = documentTaskMapper;
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
        KnowledgeBase kb = findOwned(userId, id);
        return new KnowledgeBaseResponse(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
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
        return new KnowledgeBaseResponse(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
    }

    @Transactional
    public void delete(Long userId, Long id) {
        KnowledgeBase kb = findOwned(userId, id);
        // 有文档先删文档（拒绝级联删除，避免孤儿记录）
        if (knowledgeBaseDocumentMapper.countByKnowledgeBaseId(id) > 0) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_HAS_DOCUMENTS);
        }
        this.removeById(kb.getId());
    }

    public void bindDocuments(Long userId, Long knowledgeBaseId, List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty())
            return;
        // 1. 检查知识库是否存在
        KnowledgeBase kb = findOwned(userId, knowledgeBaseId);
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
    }

    private KnowledgeBase findOwned(Long userId, Long id) {
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
        KnowledgeBase kb = findOwned(userId, knowledgeBaseId);
        List<Long> documentIds = knowledgeBaseDocumentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeBaseDocument>()
                                .eq(KnowledgeBaseDocument::getKnowledgeBaseId, knowledgeBaseId)
                ).stream()
                .map(KnowledgeBaseDocument::getFileId)
                .filter(id -> !documentDeleteIds.contains(id))
                .toList();
        // 全部删除
        knowledgeBaseDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeBaseDocument>().eq(KnowledgeBaseDocument::getKnowledgeBaseId, knowledgeBaseId));
        // 加回来
        bindDocuments(userId, knowledgeBaseId, documentIds);
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
        return knowledgeBaseDocumentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeBaseDocument>()
                                .eq(KnowledgeBaseDocument::getKnowledgeBaseId, knowledgeBaseId)
                ).stream()
                .map(KnowledgeBaseDocument::getFileId)
                .toList();
    }
}
