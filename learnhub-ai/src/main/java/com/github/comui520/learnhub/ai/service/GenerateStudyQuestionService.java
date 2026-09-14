package com.github.comui520.learnhub.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.ai.AiGenerateError;
import com.github.comui520.learnhub.ai.dto.GenerateStudyQuestionRequest;
import com.github.comui520.learnhub.ai.dto.GeneratedStudyOption;
import com.github.comui520.learnhub.ai.dto.GeneratedStudyQuestion;
import com.github.comui520.learnhub.ai.study.QuestionTypeEnum;
import com.github.comui520.learnhub.ai.utils.VectorUtil;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.knowledge.KnowledgeErrorCode;
import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import com.github.comui520.learnhub.user.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@Slf4j
public class GenerateStudyQuestionService {
    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final VectorUtil vectorUtil;
    private static final Set<String> expectedKeys = Set.of("A", "B", "C", "D");

    public GenerateStudyQuestionService(
            KnowledgeBaseService knowledgeBaseService,
            VectorStore vectorStore,
            CurrentUser currentUser,
            ObjectMapper objectMapper,
            VectorUtil vectorUtil,
            ChatClient.Builder chatClientBuilder
    ) {
        this.vectorStore = vectorStore;
        this.knowledgeBaseService = knowledgeBaseService;
        this.vectorUtil = vectorUtil;
        this.objectMapper = objectMapper;
        this.chatClient = chatClientBuilder.build();
    }

    public List<GeneratedStudyQuestion> generateStudyQuestions(GenerateStudyQuestionRequest request, Long knowledgeBaseId, Long userId) {

        List<Long> fileIds = knowledgeBaseService.listBoundFileIds(userId, knowledgeBaseId);
        if (fileIds.isEmpty()) {
            log.info("No documents found for knowledge base ID: {}", knowledgeBaseId);
            throw new BusinessException(KnowledgeErrorCode.DOCUMENT_NOT_FOUND);
        }

        String[] fileIdsStrings = fileIds.stream().map(String::valueOf).toArray(String[]::new);

        Filter.Expression filter = new FilterExpressionBuilder()
                .in("fileId", fileIdsStrings)
                .build();

        String query = request.topic() == null || request.topic().isBlank()
                ? "核心概念、定义、关键步骤、使用场景和常见错误"
                : request.topic();
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(Math.clamp(request.count() * 4L, 8, 20))
                .similarityThreshold(0.2)
                .filterExpression(filter)
                .build());

        if (hits.isEmpty()) {
            throw new BusinessException(AiGenerateError.USABLE_CONTENT_NOT_FOUND);
        }

        String context = vectorUtil.buildContext(hits);


        String system = "你是一个严格的学习题目生成器。"
                + "你只能根据用户提供的资料出题，禁止补充资料之外的事实。"
                + "如果资料不足以生成题目，应返回空数组。";

        String userPrompt = "请根据下面的资料生成 " +
                request.count() +
                " 道 " +
                request.questionType() +
                " 题。\n" +
                "出题范围：" +
                (request.topic() == null ? "覆盖资料重点" : request.topic()) +
                "\n" +
                "硬性要求：\n" +
                "1 只能输出 JSON 数组，不能输出 Markdown、解释文字或代码围栏。\n" +
                "2 每道题必须有 questionType、content、analysis、options。\n" +
                "3 options 必须恰好四项，key 必须是 A、B、C、D。必须严格大写。 \n" +
                "4 " + QuestionTypeEnum.SINGLE_CHOICE.getCode() + " 恰好一个 correct=true。\n" +
                "5 " + QuestionTypeEnum.MULTIPLE_CHOICE.getCode() + " correct 数量必须是2到3个。\n" +
                "6 analysis 只能解释资料中能证明的内容。\n" +
                "JSON 示例：\n" +
                "[{\"questionType\":\"SINGLE_CHOICE\"," +
                "\"content\":\"...\",\"analysis\":\"...\"," +
                "\"options\":[" +
                "{\"key\":\"A\",\"content\":\"...\",\"correct\":false}," +
                "{\"key\":\"B\",\"content\":\"...\",\"correct\":true}," +
                "{\"key\":\"C\",\"content\":\"...\",\"correct\":false}," +
                "{\"key\":\"D\",\"content\":\"...\",\"correct\":false}]}]\n" +
                "资料：\n" +
                context;


        String raw = chatClient.prompt()
                .system(system)
                .user(userPrompt)
                .call()
                .content();

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(AiGenerateError.AI_MODEL_RESPONSE_EMPTY);
        }

        String json = stripCodeFence(raw);

        try {
            JsonNode root = objectMapper.readTree(json);

            if (!root.isArray()) {
                root = root.get("questions");
            }

            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("AI response is not a question array");
            }

            if (root.size() != request.count()) {
                throw new IllegalArgumentException("Unexpected question count");
            }

            List<GeneratedStudyQuestion> questions = objectMapper.readerForListOf(
                    GeneratedStudyQuestion.class
            ).readValue(root);

            if (questions == null || questions.isEmpty()) {
                throw new BusinessException(AiGenerateError.AI_MODEL_RESPONSE_INVALID);
            }

            if (verifyQuestions(questions, request.count(), request.questionType())) {
                return questions;
            } else {
                throw new BusinessException(AiGenerateError.AI_MODEL_RESPONSE_INVALID);
            }

        }catch (BusinessException e){
            throw e;
        } catch (Exception e) {
            log.error("Error parsing JSON: {}", e.getMessage());
            throw new BusinessException(AiGenerateError.JSON_PARSE_FAILED);
        }
    }

    private String stripCodeFence(String raw) {
        if (raw.startsWith("```")) {
            int firstLineEnd = raw.indexOf('\n');
            int lastFence = raw.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                return raw.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        return raw;
    }

    private boolean verifyQuestions(List<GeneratedStudyQuestion> questions, int count, String questionType) {
        int realCount = questions.size();
        if (realCount != count)
            return false;
        for (GeneratedStudyQuestion question : questions) {
            String type = question.questionType();
            if (type == null || !type.equals(questionType) ||
                    question.content() == null || question.content().isBlank() ||
                    question.analysis() == null || question.analysis().isBlank() ||
                    question.options() == null || question.options().isEmpty()
            )
                return false;
            if (question.options().size() != 4)
                return false;


            int correctCount = 0;
            if (type.equals(QuestionTypeEnum.SINGLE_CHOICE.getCode())) {
                Set<String> actualKeys = new HashSet<>();
                for (GeneratedStudyOption option : question.options()) {
                    if (option.key() == null || option.content() == null || option.content().isBlank())
                        return false;
                    actualKeys.add(option.key().strip());
                    if (option.correct())
                        correctCount++;
                }
                if (correctCount != 1)
                    return false;
                if (!expectedKeys.equals(actualKeys))
                    return false;
            } else if (type.equals(QuestionTypeEnum.MULTIPLE_CHOICE.getCode())) {
                Set<String> actualKeys = new HashSet<>();
                for (GeneratedStudyOption option : question.options()) {
                    if (option.key() == null || option.content() == null)
                        return false;
                    if (option.correct())
                        correctCount++;
                    actualKeys.add(option.key().strip());

                }
                if (correctCount < 2 || correctCount >= 4)
                    return false;
                if (!expectedKeys.equals(actualKeys))
                    return false;
            } else {
                return false;
            }
        }
        return true;
    }
}
