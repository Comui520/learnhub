package com.github.comui520.learnhub.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.ai.utils.VectorUtil;
import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Service
public class RagChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorUtil vectorUtil;
    private final ObjectMapper objectMapper;

    public RagChatService(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            KnowledgeBaseService knowledgeBaseService,
            VectorUtil vectorUtil,
            ObjectMapper objectMapper
    ) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.knowledgeBaseService = knowledgeBaseService;
        this.vectorUtil = vectorUtil;
        this.objectMapper = objectMapper;
    }

    /** 检索 + 组装 + 流式回答，SSE 事件流：先 references，再逐段 content */
    public Flux<ServerSentEvent<String>> streamChat(Long userId, Long knowledgeBaseId, String question) {
        // ① 取该库绑定的文件 id（内部已校验归属，别人的库直接 404）
        List<Long> fileIds = knowledgeBaseService.listBoundFileIds(userId, knowledgeBaseId);

        String[] fileIdStrings = fileIds.stream().map(String::valueOf).toArray(String[]::new);
        // ② 空库：没有可检索的资料，不调模型，直接返回空引用事件
        if (fileIds.isEmpty()) {
            log.info("chat: kbId={}, userId={}, no bound files", knowledgeBaseId, userId);
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("references")
                    .data("[]")
                    .build());
        }

        // ③ 检索：按 fileId 过滤，取最相似的 5 块
        // ⚠️ 不能直接 .in("fileId", fileIds)：会命中可变参数重载，把整个 List 当成一个元素，
        //    Qdrant 报 "Unsupported value in IN value list. Only supports String or Number"；
        //    传 toArray() 才能让每个 fileId 成为独立元素。
        Filter.Expression filter = new FilterExpressionBuilder()
                .in("fileId", fileIdStrings)
                .build();
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .similarityThreshold(0.4)
                .topK(5)
                .filterExpression(filter)
                .build();
        List<Document> hits = vectorStore.similaritySearch(searchRequest);

        log.info("chat: kbId={}, userId={}, hits={}", knowledgeBaseId, userId, hits.size());

        // ④ 没有检索到任何资料：不调模型，直接告诉用户
        if (hits.isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("references")
                    .data("[]")
                    .build());
        }
        // ⑤ 组装 Prompt
        String fragments = vectorUtil.buildContext(hits);


        String system = "你是一个严谨的知识库助手。只根据用户提供的资料片段回答；"
                + "如果资料中没有相关信息，直接回答“资料中没有相关信息”，不要编造。";
        String userPrompt = fragments + "问题：" + question;

        // ⑥ 引用事件（先发）
        String referencesJson = buildReferencesJson(hits);
        Flux<ServerSentEvent<String>> referencesEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("references")
                        .data(referencesJson)
                        .build()
        );

        // ⑦ 内容流（逐 token 发）
        Flux<ServerSentEvent<String>> contentStream = chatClient
                .prompt()
                .system(system)
                .user(userPrompt)
                .stream()
                .content()
                .map(token -> ServerSentEvent.<String>builder()
                        .event("content")
                        .data(token)
                        .build())
                .onErrorResume(e -> {
                    log.error("chat model stream failed: kbId={}", knowledgeBaseId, e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("模型调用失败，请稍后重试")
                            .build());
                });

        return Flux.concat(referencesEvent, contentStream);
    }

    private String buildReferencesJson(List<Document> hits) {
        List<Reference> references = hits.stream()
                .map(hit -> new Reference(
                        String.valueOf(hit.getMetadata().getOrDefault("fileName", "unknown")),
                        readChunkIndex(hit)
                ))
                .toList();
        try {
            return objectMapper.writeValueAsString(references);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat references", e);
        }
    }

    private long readChunkIndex(Document document) {
        Object rawIndex = document.getMetadata().get("chunkIndex");
        if (rawIndex == null) {
            rawIndex = document.getMetadata().get("chunk_index");
        }
        if (rawIndex instanceof Number number) {
            return number.longValue();
        }
        if (rawIndex == null) {
            return -1L;
        }
        try {
            return Long.parseLong(String.valueOf(rawIndex));
        } catch (NumberFormatException e) {
            log.warn("invalid chunk index metadata: {}", rawIndex);
            return -1L;
        }
    }

    private record Reference(String fileName, long chunkIndex) {
    }
}
