package com.github.comui520.learnhub.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.github.comui520.learnhub.knowledge.entity.Document;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
    Document findByKnowledgeBaseIdAndSha256(@Param("knowledgeBaseId") Long knowledgeBaseId, @Param("sha256") String sha256);
    Document findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
