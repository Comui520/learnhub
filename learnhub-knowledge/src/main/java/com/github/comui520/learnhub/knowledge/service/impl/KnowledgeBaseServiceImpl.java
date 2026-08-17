package com.github.comui520.learnhub.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.knowledge.KnowledgeErrorCode;
import com.github.comui520.learnhub.knowledge.dto.CreateKnowledgeBaseRequest;
import com.github.comui520.learnhub.knowledge.dto.KnowledgeBaseResponse;
import com.github.comui520.learnhub.knowledge.entity.KnowledgeBase;
import com.github.comui520.learnhub.knowledge.mapper.KnowledgeBaseMapper;
import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> implements KnowledgeBaseService {
    public KnowledgeBaseResponse create(Long userId, CreateKnowledgeBaseRequest request) {
        if (this.exists(new LambdaQueryWrapper<>(KnowledgeBase.class)
                        .eq(KnowledgeBase::getUserId, userId)
                        .eq(KnowledgeBase::getName, request.name()))) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NAME_EXISTS);
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setUserId(userId);
        kb.setDescription(request.description());
        kb.setCreatedAt(LocalDateTime.now());
        // todo: 创建时间有问题
        this.save(kb);
        return new KnowledgeBaseResponse(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
    }

    @Override
    public IPage<KnowledgeBaseResponse> page(Long userId, int page, int size) {
        return this.page(new Page<KnowledgeBase>(page, size),
                        new LambdaQueryWrapper<KnowledgeBase>()
                                .eq(KnowledgeBase::getUserId, userId)
                                .orderByDesc(KnowledgeBase::getCreatedAt)
                )
                .convert(knowledgeBase -> new KnowledgeBaseResponse(
                        knowledgeBase.getId(),
                        knowledgeBase.getName(),
                        knowledgeBase.getDescription(),
                        knowledgeBase.getCreatedAt()
                ));
    }

    @Override
    public void delete(Long userId, Long id) {
        KnowledgeBase kb = this.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getUserId, userId)
                .one();
        if (kb == null) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        this.removeById(id);
    }
}
