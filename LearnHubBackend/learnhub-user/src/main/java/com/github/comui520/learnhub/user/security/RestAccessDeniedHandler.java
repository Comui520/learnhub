package com.github.comui520.learnhub.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import com.github.comui520.learnhub.common.exception.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;


    public RestAccessDeniedHandler(
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.failure(CommonErrorCode.FORBIDDEN));
    }
}
