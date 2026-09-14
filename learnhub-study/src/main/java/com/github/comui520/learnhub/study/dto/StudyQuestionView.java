package com.github.comui520.learnhub.study.dto;

import java.util.List;

public record StudyQuestionView(
        Long id,
        Long knowledgeBaseId,
        String questionType,
        String content,
        List<StudyOptionResponse> options
) {}