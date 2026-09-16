package com.github.comui520.learnhub.study.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.comui520.learnhub.study.dto.StudyQuestionView;
import com.github.comui520.learnhub.study.entity.StudyQuestion;
import jakarta.validation.constraints.NotNull;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StudyQuestionMapper extends BaseMapper<StudyQuestion> {

    IPage<StudyQuestionView> selectQuestionViews(@Param("page") Page<StudyQuestionView> page, @Param("userId") Long userId, @NotNull @Param("knowledgeBaseId") Long knowledgeBaseId, @Param("questionType") String questionType);

}
