package com.github.comui520.learnhub.study.dto;

import java.util.List;

public record StudyQuestionResponse(
        Long id,
        Long knowledgeBaseId,
        String questionType,
        String content,
        String analysis,
        List<StudyOptionResponse> options
) {}
