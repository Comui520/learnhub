package com.github.comui520.learnhub.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.comui520.learnhub.knowledge.dto.DocumentResponse;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentFileMapper extends BaseMapper<DocumentFile> {

    /** 同一用户是否已上传过相同内容（sha256）的文件 */
    @Select("SELECT * FROM `document_file` WHERE user_id = #{userId} AND sha256 = #{sha256} LIMIT 1")
    DocumentFile findByUserIdAndSha256(@Param("userId") Long userId, @Param("sha256") String sha256);

    List<DocumentResponse> selectByIdsAndUserId(@Param("documentIds") List<Long> documentIds, @Param("userId") Long userId, @Param("knowledgeBaseId") Long knowledgeBaseId);
}
