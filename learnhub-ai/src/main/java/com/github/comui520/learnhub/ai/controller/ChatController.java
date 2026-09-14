package com.github.comui520.learnhub.ai.controller;

import com.github.comui520.learnhub.credit.CreditErrorCode;
import com.github.comui520.learnhub.ai.dto.ChatRequest;
import com.github.comui520.learnhub.ai.redis.AiRedisProperties;
import com.github.comui520.learnhub.ai.service.RagChatService;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.credit.service.CreditService;
import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
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

    public ChatController(
            RagChatService ragChatService,
            CurrentUser currentUser,
            RedissonClient redisson,
            AiRedisProperties aiRedisProperties,
            KnowledgeBaseService knowledgeBaseService,
            CreditService creditService
    ) {
        this.ragChatService = ragChatService;
        this.currentUser = currentUser;
        this.redisson = redisson;
        this.aiRedisProperties = aiRedisProperties;
        this.knowledgeBaseService = knowledgeBaseService;
        this.creditService = creditService;
    }

    @PostMapping(
            value = "/{id}/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Chat with knowledge base", description = "Chat with knowledge base")
    public Flux<ServerSentEvent<String>> chat(
            @PathVariable Long id,
            @Valid @RequestBody ChatRequest chatRequest
            ){
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
        return ragChatService.streamChat(userId, id, chatRequest.question());
    }

    private String buildKey(Long userId){
        return aiRedisProperties.getAiChatKey() + userId;
    }
}
