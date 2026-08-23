package com.github.comui520.learnhub.knowledge;

import com.github.comui520.learnhub.common.exception.ErrorCode;

public enum KnowledgeErrorCode implements ErrorCode {
    KNOWLEDGE_BASE_NOT_FOUND("KB_ERROR_0404", "Knowledge base not found", 404),
    KNOWLEDGE_BASE_NAME_EXISTS("KB_ERROR_0409", "Knowledge base name already exists", 409),
    KNOWLEDGE_BASE_HAS_DOCUMENTS("KB_ERROR_0409", "Knowledge base has documents, delete them first", 409),
    DUPLICATE_DOCUMENT("DOC_ERROR_0409", "Document already exists", 409),
    DOCUMENT_NOT_FOUND("DOC_ERROR_0404", "Document not found", 404),
    TASK_NOT_FOUND("TASK_ERROR_0404", "Task not found", 404),
    TASK_STATUS_NOT_FAILED("TASK_ERROR_409", "Task status is not failed", 409);

    private final String code;
    private final String message;
    private final Integer httpStatus;

    KnowledgeErrorCode(String code, String message, Integer httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return this.code;
    }

    @Override
    public String message() {
        return this.message;
    }

    @Override
    public Integer httpStatus() {
        return this.httpStatus;
    }
}
