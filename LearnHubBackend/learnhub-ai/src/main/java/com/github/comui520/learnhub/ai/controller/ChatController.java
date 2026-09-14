package com.github.comui520.learnhub.ai.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import com.github.comui520.learnhub.common.exception.ErrorCode;
import com.github.comui520.learnhub.credit.CreditErrorCode;
import com.github.comui520.learnhub.ai.dto.ChatRequest;
import com.github.comui520.learnhub.ai.redis.AiRedisProperties;
import com.github.comui520.learnhub.ai.service.RagChatService;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.credit.service.CreditService;
import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

@Tag(name = "Chat", description = "知识库问答")
@Slf4j
@RestController
@RequestMapping("api/v1/knowledge-bases")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final RagChatService ragChatService;
    private final CurrentUser currentUser;
    private final RedissonClient redisson;
    private final AiRedisProperties aiRedisProperties;
    private final CreditService creditService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public ChatController(
            RagChatService ragChatService,
            CurrentUser currentUser,
            RedissonClient redisson,
            AiRedisProperties aiRedisProperties,
            KnowledgeBaseService knowledgeBaseService,
            CreditService creditService,
            ObjectMapper objectMapper
    ) {
        this.ragChatService = ragChatService;
        this.currentUser = currentUser;
        this.redisson = redisson;
        this.aiRedisProperties = aiRedisProperties;
        this.knowledgeBaseService = knowledgeBaseService;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(
            value = "/{id}/chat",
            produces = {
                    MediaType.TEXT_EVENT_STREAM_VALUE,
                    MediaType.APPLICATION_JSON_VALUE
            })
    @Operation(summary = "Chat with knowledge base", description = "Chat with knowledge base")
    public ResponseEntity<?> chat(
            @PathVariable Long id,
            @Valid @RequestBody ChatRequest chatRequest,
            HttpServletRequest request
    ) {
        try {
            Long userId = currentUser.currentUserId();
            knowledgeBaseService.findOwned(userId, id);

            String key = buildKey(userId);
            RRateLimiter limiter = redisson.getRateLimiter(key);
            // 只负责初始化限流规则；重复调用不会重置已有规则。
            limiter.trySetRate(RateType.PER_CLIENT, 5, Duration.ofSeconds(10));
            // 一次请求只消耗一个令牌。
            if (!limiter.tryAcquire()) {
                throw new BusinessException(CreditErrorCode.RATE_LIMITED);
            }
            if (!creditService.consume(userId, BigDecimal.ONE, "chat:" + UUID.randomUUID())) {
                throw new BusinessException(CreditErrorCode.INSUFFICIENT_CREDIT);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(ragChatService.streamChat(userId, id, chatRequest.question()));
        } catch (BusinessException exception) {
            // SSE 请求一旦开始输出，后续错误只能通过 error 事件传递。
            // 这里的校验发生在流开始前，因此仍保留业务 HTTP 状态码（例如 402/429）。
            return errorResponse(exception.getErrorCode(), request);
        } catch (Exception exception) {
            log.error("Chat request failed before SSE stream started, knowledgeBaseId={}", id, exception);
            return errorResponse(CommonErrorCode.INTERNAL_ERROR, request);
        }
    }

    private ResponseEntity<?> errorResponse(
            ErrorCode errorCode,
            HttpServletRequest request
    ) {
        String data;
        try {
            data = objectMapper.writeValueAsString(ApiResponse.failure(errorCode));
        } catch (JsonProcessingException exception) {
            log.error("Unable to serialize SSE error response, code={}", errorCode.code(), exception);
            data = "{\"code\":\"COMMON_0500\",\"message\":\"INTERNAL_ERROR\"}";
        }

        String accept = request.getHeader("Accept");
        if (accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            Flux<ServerSentEvent<String>> body = Flux.just(
                    ServerSentEvent.<String>builder()
                            .event("error")
                            .data(data)
                            .build()
            );
            return ResponseEntity.status(errorCode.httpStatus())
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(body);
        }

        return ResponseEntity.status(errorCode.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(errorCode));
    }

    private String buildKey(Long userId){
        return aiRedisProperties.getAiChatKey() + userId;
    }
}
