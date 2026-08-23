package com.github.comui520.learnhub.ai.controller;

import com.github.comui520.learnhub.ai.dto.ChatRequest;
import com.github.comui520.learnhub.ai.service.RagChatService;
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@Tag(name = "Chat", description = "知识库问答")
@Slf4j
@RestController
@RequestMapping("api/v1/knowledge-bases")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final RagChatService ragChatService;
    private final CurrentUser currentUser;

    public ChatController(
            RagChatService ragChatService,
            CurrentUser currentUser
    ) {
        this.ragChatService = ragChatService;
        this.currentUser = currentUser;
    }

    @PostMapping(
            value = "/{id}/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Chat with knowledge base", description = "Chat with knowledge base")
    public Flux<ServerSentEvent<String>> chat(
            @PathVariable Long id,
            @Valid @RequestBody ChatRequest chatRequest
            ){
        return ragChatService.streamChat(currentUser.currentUserId(), id, chatRequest.question());
    }
}
