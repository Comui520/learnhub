package com.github.comui520.learnhub.knowledge;

import lombok.Getter;

@Getter
public enum DocumentTaskStatus {
    PENDING("PENDING"),
    RUNNING("RUNNING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    ;

    private final String Status;

    DocumentTaskStatus(String status) {
        this.Status = status;
    }
}
