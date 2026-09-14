package com.github.comui520.learnhub.study.dto;

import java.time.LocalDateTime;

public record WrongQuestionResponse(
        Long id,
        Long questionId,
        Integer wrongCount,
        LocalDateTime nextReviewAt,
        Boolean mastered
) {}
