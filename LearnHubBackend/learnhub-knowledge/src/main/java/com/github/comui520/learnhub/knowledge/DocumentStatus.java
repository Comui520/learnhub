package com.github.comui520.learnhub.knowledge;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Getter
public enum DocumentStatus {
    UPLOADED("UPLOADED"),
    PARSING("PARSING"),
    EMBEDDING("EMBEDDING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED")
;
    private final String status;

    DocumentStatus(String status) {
        this.status = status;
    }
}
