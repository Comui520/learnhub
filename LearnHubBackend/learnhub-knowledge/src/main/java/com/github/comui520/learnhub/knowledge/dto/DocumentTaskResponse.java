package com.github.comui520.learnhub.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

public record DocumentTaskResponse (
        Long id,

        Long fileId,

        String fileName,

        String type,

        String status,

        String lastError,

        LocalDateTime createdAt
) {


}
