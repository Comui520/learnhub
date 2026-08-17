package com.github.comui520.learnhub.knowledge.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.knowledge.dto.DocumentResponse;
import com.github.comui520.learnhub.knowledge.entity.Document;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService extends IService<Document> {

    public DocumentResponse upload(Long userId, Long knowledgeBaseId, MultipartFile file);
}
