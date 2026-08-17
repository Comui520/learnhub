package com.github.comui520.learnhub.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.github.comui520.learnhub.knowledge.dto.CreateKnowledgeBaseRequest;
import com.github.comui520.learnhub.knowledge.dto.KnowledgeBaseResponse;
import com.github.comui520.learnhub.knowledge.entity.KnowledgeBase;

public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    public KnowledgeBaseResponse create(Long userId, CreateKnowledgeBaseRequest request);

    public IPage<KnowledgeBaseResponse> page(Long userId, int page, int size);

    void delete(Long userId, Long id);
}
