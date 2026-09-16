package com.github.comui520.learnhub.study.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.ai.dto.GenerateStudyQuestionRequest;
import com.github.comui520.learnhub.ai.dto.GeneratedStudyOption;
import com.github.comui520.learnhub.ai.dto.GeneratedStudyQuestion;
import com.github.comui520.learnhub.ai.service.GenerateStudyQuestionService;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import com.github.comui520.learnhub.study.OptionTypeEnum;
import com.github.comui520.learnhub.study.StudyErrorCode;
import com.github.comui520.learnhub.study.dto.*;
import com.github.comui520.learnhub.study.entity.StudyAttempt;
import com.github.comui520.learnhub.study.entity.StudyQuestion;
import com.github.comui520.learnhub.study.entity.StudyQuestionOption;
import com.github.comui520.learnhub.study.entity.StudyWrongQuestion;
import com.github.comui520.learnhub.study.mapper.StudyAttemptMapper;
import com.github.comui520.learnhub.study.mapper.StudyQuestionMapper;
import com.github.comui520.learnhub.study.mapper.StudyQuestionOptionMapper;
import com.github.comui520.learnhub.study.mapper.StudyWrongQuestionMapper;
import com.github.comui520.learnhub.study.redis.StudyRedisProperties;
import com.github.comui520.learnhub.user.CurrentUser;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@Service
@Slf4j
public class StudyService extends ServiceImpl<StudyQuestionMapper, StudyQuestion> {

    private static final Set<String> ALLOWED_OPTION_KEYS = Set.of("A", "B", "C", "D");

    private final StudyQuestionMapper questionMapper;
    private final StudyAttemptMapper attemptMapper;
    private final StudyQuestionOptionMapper questionOptionMapper;
    private final StudyWrongQuestionMapper wrongQuestionMapper;
    private final CurrentUser currentUser;
    private final StringRedisTemplate redisTemplate;
    private final StudyRedisProperties studyRedisProperties;
    private final ObjectMapper objectMapper;
    private final GenerateStudyQuestionService generateService;
    private final TransactionTemplate transactionTemplate;
    private final KnowledgeBaseService knowledgeBaseService;

    public StudyService(
            StudyQuestionMapper questionMapper,
            StudyAttemptMapper attemptMapper,
            StudyQuestionOptionMapper questionOptionMapper,
            StudyWrongQuestionMapper wrongQuestionMapper,
            CurrentUser currentUser,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            GenerateStudyQuestionService generateService,
            StudyRedisProperties studyRedisProperties,
            KnowledgeBaseService knowledgeBaseService,
            PlatformTransactionManager transactionManager
    ) {
        this.questionMapper = questionMapper;
        this.attemptMapper = attemptMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.wrongQuestionMapper = wrongQuestionMapper;
        this.currentUser = currentUser;
        this.generateService = generateService;
        this.objectMapper = objectMapper;
        this.studyRedisProperties = studyRedisProperties;
        this.redisTemplate = redisTemplate;
        this.knowledgeBaseService = knowledgeBaseService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public StudyQuestionView getQuestionView(Long questionId) {
        Long userId = currentUser.currentUserId();
        StudyQuestion question = findOwnedQuestion(questionId, userId);

        String key = studyRedisProperties.getStdQstOptKey() + questionId;
        //看redis有没有存
        List<String> cached = redisTemplate.opsForList().range(key, 0, -1);
        if (cached != null && !cached.isEmpty()) {
            try {
                List<StudyQuestionOption> options = new ArrayList<>(cached.size());
                for (String json : cached) {
                    options.add(objectMapper.readValue(json, StudyQuestionOption.class));
                }
                return toQuestionView(question, options);
            } catch (JsonProcessingException e) {
                log.warn("cache corrupted, fallback to db, questionId={}", questionId, e);
                redisTemplate.delete(key);
                // 不 return，往下走去查 DB
            }
        }

        List<StudyQuestionOption> options = questionOptionMapper.selectList(
                new LambdaQueryWrapper<StudyQuestionOption>()
                        .eq(StudyQuestionOption::getQuestionId, questionId)
                        .orderByAsc(StudyQuestionOption::getOptionKey)
        );

        if (options != null && !options.isEmpty()) {
            try {
                List<String> jsonOptions = options.stream()
                        .map(option -> {
                            try {
                                return objectMapper.writeValueAsString(option);
                            } catch (JsonProcessingException e) {
                                log.warn("convert question option to json failed, option={}", option, e);
                                throw new UncheckedIOException(e);
                            }
                        }).toList();
                redisTemplate.opsForList().rightPushAll(key, jsonOptions);
                redisTemplate.expire(key, studyRedisProperties.getExpire(), TimeUnit.SECONDS);
                return toQuestionView(question, options);
            } catch (Exception e) {
                log.warn("convert question option to json failed", e);
            }
            return toQuestionView(question, options);
        }
        throw new BusinessException(StudyErrorCode.QUESTION_NO_OPTIONS);
    }

    @Transactional
    public StudyAnswerResponse submitAnswer(
            Long questionId,
            List<String> submittedOptions
    ) {
        Long userId = currentUser.currentUserId();
        if (submittedOptions == null || submittedOptions.isEmpty()) {
            throw new BusinessException(StudyErrorCode.ANSWER_INVALID);
        }

        StudyQuestion question = findOwnedQuestion(questionId, userId);
        List<StudyQuestionOption> questionOptions = questionOptionMapper.selectList(
                new LambdaQueryWrapper<StudyQuestionOption>()
                        .eq(StudyQuestionOption::getQuestionId, questionId)
                        .orderByAsc(StudyQuestionOption::getOptionKey)
        );

        if (questionOptions.isEmpty()) {
            throw new BusinessException(StudyErrorCode.QUESTION_NO_OPTIONS);
        }

        Set<String> availableKeys = questionOptions.stream()
                .map(StudyQuestionOption::getOptionKey)
                .filter(Objects::nonNull)
                .map(this::normalizeOptionKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> selectedKeys = submittedOptions.stream()
                .map(this::normalizeOptionKey)
                .toList();

        if (!verifyAnswerOptions(selectedKeys, availableKeys)) {
            throw new BusinessException(StudyErrorCode.ANSWER_INVALID);
        }

        if (OptionTypeEnum.SINGLE_CHOICE.getCode().equals(question.getQuestionType())
                && selectedKeys.size() != 1) {
            throw new BusinessException(StudyErrorCode.ANSWER_INVALID);
        }
        if (OptionTypeEnum.MULTIPLE_CHOICE.getCode().equals(question.getQuestionType())
                && selectedKeys.isEmpty()) {
            throw new BusinessException(StudyErrorCode.ANSWER_INVALID);
        }
        if (!OptionTypeEnum.SINGLE_CHOICE.getCode().equals(question.getQuestionType())
                && !OptionTypeEnum.MULTIPLE_CHOICE.getCode().equals(question.getQuestionType())) {
            throw new BusinessException(StudyErrorCode.UNSUPPORTED_OPERATION);
        }

        List<String> correctKeys = questionOptions.stream()
                .filter(option -> Integer.valueOf(1).equals(option.getIsCorrect()))
                .map(StudyQuestionOption::getOptionKey)
                .filter(Objects::nonNull)
                .map(this::normalizeOptionKey)
                .sorted()
                .toList();

        if (correctKeys.isEmpty()) {
            throw new BusinessException(StudyErrorCode.QUESTION_NO_CORRECT_OPTION);
        }
        if (OptionTypeEnum.SINGLE_CHOICE.getCode().equals(question.getQuestionType())
                && correctKeys.size() != 1) {
            throw new BusinessException(
                    StudyErrorCode.SINGLE_CHOICE_QUESTION_MULTIPLE_CORRECT_OPTIONS);
        }

        List<String> normalizedSelectedKeys = selectedKeys.stream().sorted().toList();
        boolean correct = normalizedSelectedKeys.equals(correctKeys);
        String storedAnswer = String.join(",", normalizedSelectedKeys);

        LocalDateTime now = LocalDateTime.now();
        StudyAttempt attempt = new StudyAttempt();
        attempt.setUserId(userId);
        attempt.setQuestionId(questionId);
        attempt.setAnswer(storedAnswer);
        attempt.setCorrect(correct ? 1 : 0);
        attempt.setCreatedAt(now);
        attemptMapper.insert(attempt);

        if (!correct) {
            recordWrongQuestion(userId, questionId, now);
        }

        return new StudyAnswerResponse(
                questionId,
                normalizedSelectedKeys,
                correct,
                correctKeys,
                question.getAnalysis()
        );
    }

    public IPage<StudyQuestionView> getQuestionViews(QuestionViewPageRequest request) {
        Long userId = currentUser.currentUserId();
        knowledgeBaseService.findOwned(userId, request.getKnowledgeBaseId());
        Page<StudyQuestionView> page = new Page<>(request.getPage(), request.getSize());
        return questionMapper.selectQuestionViews(page, userId, request.getKnowledgeBaseId(), request.getQuestionType());
    }

    private StudyQuestion findOwnedQuestion(Long questionId, Long userId) {
        StudyQuestion question = questionMapper.selectOne(
                new LambdaQueryWrapper<StudyQuestion>()
                        .eq(StudyQuestion::getId, questionId)
                        .eq(StudyQuestion::getUserId, userId)
        );
        if (question == null) {
            throw new BusinessException(StudyErrorCode.QUESTION_NOT_FOUND);
        }
        return question;
    }

    private void recordWrongQuestion(Long userId, Long questionId, LocalDateTime now) {
        StudyWrongQuestion wrong = wrongQuestionMapper.selectOne(
                new LambdaQueryWrapper<StudyWrongQuestion>()
                        .eq(StudyWrongQuestion::getUserId, userId)
                        .eq(StudyWrongQuestion::getQuestionId, questionId)
        );

        if (wrong == null) {
            wrong = new StudyWrongQuestion();
            wrong.setUserId(userId);
            wrong.setQuestionId(questionId);
            wrong.setWrongCount(1);
            wrong.setMastered(0);
            wrong.setNextReviewAt(now.plusDays(1));
            wrong.setUpdatedAt(now);
            wrongQuestionMapper.insert(wrong);
            return;
        }

        wrong.setWrongCount(wrong.getWrongCount() + 1);
        wrong.setMastered(0);
        wrong.setNextReviewAt(now.plusDays(1));
        wrong.setUpdatedAt(now);
        wrongQuestionMapper.updateById(wrong);
    }


    public List<StudyQuestionView> generateQuestion(GenerateStudyQuestionRequest request, Long knowledgeBaseId) {
        Long userId = currentUser.currentUserId();

        // 模型调用发生在事务外，避免网络延迟长期占用数据库连接。
        List<GeneratedStudyQuestion> generatedList = generateService.generateStudyQuestions(request, knowledgeBaseId, userId);
        List<StudyQuestionView> persisted = transactionTemplate.execute(status ->
                persistGeneratedQuestions(generatedList, userId, knowledgeBaseId));
        return persisted == null ? List.of() : persisted;
    }

    private List<StudyQuestionView> persistGeneratedQuestions(
            List<GeneratedStudyQuestion> generatedList,
            Long userId,
            Long knowledgeBaseId
    ) {
        List<StudyQuestionView> viewList = new ArrayList<>(generatedList.size());
        LocalDateTime now = LocalDateTime.now();
        List<StudyQuestion> questionList = generatedList.stream().map(g -> new StudyQuestion(
                null, userId, knowledgeBaseId, g.questionType(), g.content(), g.analysis(), now
        )).toList();

        questionMapper.insert(questionList);

        for (int i = 0; i < generatedList.size(); i++) {
            StudyQuestion question = questionList.get(i);
            List<GeneratedStudyOption> options = generatedList.get(i).options();
            List<StudyQuestionOption> optionList = options.stream()
                    .map(option -> new StudyQuestionOption(
                            null, question.getId(), option.key(), option.content(), option.correct() ? 1 : 0
                    ))
                    .toList();
            questionOptionMapper.insert(optionList);
            viewList.add(toQuestionView(question, optionList));
        }
        return viewList;
    }

    private String normalizeOptionKey(String optionKey) {
        return optionKey == null ? null : optionKey.trim().toUpperCase(Locale.ROOT);
    }

    private boolean verifyAnswerOptions(
            List<String> selectedKeys,
            Set<String> availableKeys
    ) {
        boolean hasBlank = selectedKeys.stream()
                .anyMatch(key -> key == null || key.isBlank());
        boolean hasDuplicate = new HashSet<>(selectedKeys).size() != selectedKeys.size();
        boolean containsUnknownKey = !availableKeys.containsAll(selectedKeys)
                || selectedKeys.stream().anyMatch(key -> !ALLOWED_OPTION_KEYS.contains(key));
        return !(hasBlank || hasDuplicate || containsUnknownKey);
    }

    private StudyQuestionView toQuestionView(
            StudyQuestion question,
            List<StudyQuestionOption> options
    ) {
        List<StudyOptionResponse> optionResponses = options.stream()
                .map(option -> new StudyOptionResponse(
                        option.getOptionKey(),
                        option.getContent()
                ))
                .toList();

        return new StudyQuestionView(
                question.getId(),
                question.getKnowledgeBaseId(),
                question.getQuestionType(),
                question.getContent(),
                optionResponses
        );
    }

    public QuestionDetail getQuestionDetail(Long questionId) {
        Long userId = currentUser.currentUserId();
        StudyQuestion studyQuestion = questionMapper.selectOne(
                new LambdaQueryWrapper<StudyQuestion>()
                        .eq(StudyQuestion::getId, questionId)
                        .eq(StudyQuestion::getUserId, userId)
        );
        if (studyQuestion == null)
            throw new BusinessException(StudyErrorCode.QUESTION_NOT_FOUND);
        List<StudyQuestionOption> studyQuestionOption = questionOptionMapper.selectList(
                new LambdaQueryWrapper<StudyQuestionOption>()
                        .eq(StudyQuestionOption::getQuestionId, questionId)
        );

        return toQuestionDetail(studyQuestion, studyQuestionOption);
    }

    private QuestionDetail toQuestionDetail(StudyQuestion question, List<StudyQuestionOption> options) {
        return new QuestionDetail(
                question.getQuestionType(),
                question.getContent(),
                question.getAnalysis(),
                options.stream().map(option -> new OptionDetail(
                        option.getOptionKey(),
                        option.getContent(),
                        option.getIsCorrect() == 1
                )).toList()
        );
    }

    @Transactional
    public void deleteQuestion(Long questionId) {
        Long userId = currentUser.currentUserId();
        int rows = questionMapper.delete(new LambdaQueryWrapper<StudyQuestion>()
                .eq(StudyQuestion::getId, questionId)
                .eq(StudyQuestion::getUserId, userId)
        );
        if (rows == 0)
            throw new BusinessException(StudyErrorCode.QUESTION_NOT_FOUND);
        questionOptionMapper.delete(new LambdaQueryWrapper<StudyQuestionOption>()
                .eq(StudyQuestionOption::getQuestionId, questionId)
        );
        wrongQuestionMapper.delete(new LambdaQueryWrapper<StudyWrongQuestion>()
                .eq(StudyWrongQuestion::getQuestionId, questionId)
        );
    }

    @Transactional
    public void deleteQuestionBatch(List<Long> ids) {
        Long userId = currentUser.currentUserId();
        List<Long> ownedIds = questionMapper.selectList(
                new LambdaQueryWrapper<StudyQuestion>()
                        .select(StudyQuestion::getId)
                        .in(StudyQuestion::getId, ids)
                        .eq(StudyQuestion::getUserId, userId)
        ).stream().map(StudyQuestion::getId).toList();
        if (ownedIds.isEmpty())
            throw new BusinessException(StudyErrorCode.QUESTION_NOT_FOUND);

        questionOptionMapper.delete(
                new LambdaQueryWrapper<StudyQuestionOption>()
                        .in(StudyQuestionOption::getQuestionId, ownedIds)
        );
        wrongQuestionMapper.delete(new LambdaQueryWrapper<StudyWrongQuestion>()
                .in(StudyWrongQuestion::getQuestionId, ownedIds)
        );
        questionMapper.deleteByIds(ownedIds);
    }
}
