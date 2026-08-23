package com.github.comui520.learnhub.ai.service;

import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClientBuilder;
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

    public RagChatService(
            ChatClient.Builder chatClientBUilder,
            VectorStore vectorStore,
            KnowledgeBaseService knowledgeBaseService
    ) {
        this.chatClient = chatClientBUilder.build();
        this.vectorStore = vectorStore;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 检索 + 组装 + 流式回答，SSE 事件流：先 references，再逐段 content */
    public Flux<ServerSentEvent<String>> streamChat(Long userId, Long knowledgeBaseId, String question) {
        // ① 取该库绑定的文件 id（内部已校验归属，别人的库直接 404）
        List<Long> fileIds = knowledgeBaseService.listBoundFileIds(userId, knowledgeBaseId);

        // ② 检索：按 fileId 过滤，取最相似的 5 块
        Filter.Expression filter = new FilterExpressionBuilder()
                .in("fileId", fileIds)
                .build();
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(5)
                .filterExpression(filter)
                .build();
        List<Document> hits = vectorStore.similaritySearch(searchRequest);

        log.info("chat: kbId={}, userId={}, hits={}", knowledgeBaseId, userId, hits.size());

        // ③ 没有检索到任何资料：不调模型，直接告诉用户
        if (hits.isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("references")
                    .data("[]")
                    .build());
        }
        // ④ 组装 Prompt
        StringBuilder fragments = new StringBuilder();
        for (Document hit : hits) {
            String fileName = (String) hit.getMetadata().get("fileName");
            Integer chunkIndex = (Integer) hit.getMetadata().get("chunkIndex");
            fragments.append("【来源：")
                    .append(fileName)
                    .append(" 第")
                    .append(chunkIndex)
                    .append("块】\n")
                    .append(hit.getText())
                    .append("\n\n");
        }

        String system = "你是一个严谨的知识库助手。只根据用户提供的资料片段回答；"
                + "如果资料中没有相关信息，直接回答“资料中没有相关信息”，不要编造。";
        String userPrompt = fragments + "问题：" + question;

        // ⑤ 引用事件（先发）
        String referencesJson = buildReferencesJson(hits);
        Flux<ServerSentEvent<String>> referencesEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("references")
                        .data(referencesJson)
                        .build()
        );

        // ⑥ 内容流（逐 token 发）
        Flux<ServerSentEvent<String>> contentStream = chatClient
                .prompt()
                .system(system)
                .user(userPrompt)
                .stream()
                .content()
                .map(token -> ServerSentEvent.<String>builder()
                        .event("content")
                        .data(token)
                        .build());

        return Flux.concat(referencesEvent, contentStream);
    }

    private String buildReferencesJson(List<Document> hits) {
        // 把 fileName + chunkIndex 拼成 JSON 数组字符串，前端用来展示引用
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            String fileName = String.valueOf(hits.get(i).getMetadata().get("fileName"));
            Integer chunkIndex = (Integer) hits.get(i).getMetadata().get("chunkIndex");
            sb.append("{\"fileName\":\"")
                    .append(fileName)
                    .append("\",\"chunkIndex\":")
                    .append(chunkIndex)
                    .append("}");
        }
        return sb.append("]").toString();
    }
}
