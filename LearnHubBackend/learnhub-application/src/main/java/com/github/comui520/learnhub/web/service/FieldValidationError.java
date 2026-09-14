package com.github.comui520.learnhub.web.service;

public record FieldValidationError (
        String field,
        String message
)
{ }
