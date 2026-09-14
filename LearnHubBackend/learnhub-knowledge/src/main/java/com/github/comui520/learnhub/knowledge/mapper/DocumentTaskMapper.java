package com.github.comui520.learnhub.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.comui520.learnhub.knowledge.dto.DocumentTaskResponse;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentTaskMapper extends BaseMapper<DocumentTask> {

    @Select("select * from `document_task` where `file_id` = #{fileId} limit 1")
    DocumentTask findByFileId(@Param("fileId") Long fileId);

    IPage<DocumentTaskResponse> page(Page<DocumentTaskResponse> page, @Param("knowledgeBaseId") Long knowledgeBaseId, @Param("status")    String status);
}
