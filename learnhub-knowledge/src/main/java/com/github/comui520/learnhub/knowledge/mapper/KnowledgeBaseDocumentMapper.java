package com.github.comui520.learnhub.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.comui520.learnhub.knowledge.dto.DocumentRow;
import com.github.comui520.learnhub.knowledge.entity.KnowledgeBaseDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeBaseDocumentMapper extends BaseMapper<KnowledgeBaseDocument> {

    /** 按关联 id 查，且文件必须属于当前用户（join document_file 做归属校验，数据隔离） */
    @Select("""
            SELECT kbd.*
            FROM `knowledge_base_document` kbd
                     JOIN `document_file` df ON df.id = kbd.file_id
            WHERE kbd.id = #{id}
              AND df.user_id = #{userId}
            LIMIT 1
            """)
    KnowledgeBaseDocument findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 知识库里有多少个文档（删除知识库前检查） */
    @Select("SELECT COUNT(*) FROM `knowledge_base_document` WHERE knowledge_base_id = #{knowledgeBaseId}")
    long countByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);

    /** 文件是否还被任何知识库引用（删除关联后检查） */
    @Select("SELECT COUNT(*) FROM `knowledge_base_document` WHERE file_id = #{fileId}")
    long countByFileId(@Param("fileId") Long fileId);

    /** 同库去重：该知识库是否已有相同内容的文件 */
    @Select("""
            SELECT COUNT(*)
            FROM `knowledge_base_document` kbd
                     JOIN `document_file` df ON df.id = kbd.file_id
            WHERE kbd.knowledge_base_id = #{knowledgeBaseId}
              AND df.sha256 = #{sha256}
            """)
    long countByKnowledgeBaseIdAndSha256(@Param("knowledgeBaseId") Long knowledgeBaseId,
                                         @Param("sha256") String sha256);

    /** 文档列表分页：关联表 join 文件表，带 user_id 数据隔离（XML 实现） */
    IPage<DocumentRow> selectPageWithFile(Page<DocumentRow> page,
                                          @Param("userId") Long userId,
                                          @Param("knowledgeBaseId") Long knowledgeBaseId);

}
