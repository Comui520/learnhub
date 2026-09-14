# Session A：学习题目与答题判分

> 这一节实现一个可运行的答题闭环：当前用户获取自己拥有的题目，提交选项编码，后端根据数据库中的正确答案判分，并记录答题历史；答错时维护错题状态。
>
> 本节支持两种题型：`SINGLE_CHOICE`（单选）和 `MULTIPLE_CHOICE`（多选）。前端只提交业务上的选项编码（例如 `A`、`B`、`C`、`D`），绝不提交 `study_question_option.id`。

## 0. 先明确本节到底要解决什么问题

一张题目表不能解决全部需求，因为题目、选项、答题历史、错题状态的生命周期不同：

```text
study_question              一道题的主体
        │ 1:N
study_question_option       A/B/C/D 等选项，其中 is_correct 只在后端使用

study_question 1:N study_attempt
        每次提交都新增一条历史记录，不能覆盖上一次回答

user + question 1:1 study_wrong_question
        当前错题状态，同一用户同一道题只有一行，wrong_count 递增
```

## 0.1 题目详情缓存：为什么可以缓存，什么时候必须失效

如果你给 `getQuestionView` 增加了 Redis 缓存，推荐采用 Cache-Aside（旁路缓存）模式：

```text
1. 先查数据库确认题目存在且属于当前用户
2. 再用 questionId 查询 Redis 中的选项
3. 命中：反序列化选项并组装响应
4. 未命中：查询数据库选项，写入 Redis，再返回
```

先做第 1 步很重要。即使缓存 key 只使用 `questionId`，也不能先从缓存返回，因为“是否属于当前用户”的判断必须由数据库业务查询完成。

当前题目和选项创建后主要是只读数据，所以缓存收益比较稳定。但只要将来增加以下任何操作，就必须删除对应缓存：

```text
修改题干或题型       删除 std:qst:{questionId}
新增、修改、删除选项  删除 std:qst:opt:{questionId}
删除题目              先保证归属校验，再删除缓存
```

这就是缓存一致性的基本规则：**修改数据库成功后，使旧缓存失效**。不要在修改时只更新数据库而忘记 Redis，否则下一次读取可能仍然看到旧数据。

还要注意三个工程细节：

- Redis 连接失败时，读取接口应尽量回退数据库；缓存是加速层，不应成为唯一数据源。
- 缓存可以设置 TTL，例如 3600 秒，避免异常情况下永久保存旧数据。
- 你当前把 `StudyQuestionOption` 序列化进 Redis，其中包含 `isCorrect`。这不会直接暴露给 API，因为 `StudyOptionResponse` 不返回它；但更稳妥的做法是缓存只读的选项 DTO，避免把判题字段放入缓存。

暂时不需要为一次缓存 miss 引入分布式锁。多个请求同时回源时最多重复写几次相同缓存，先保证正确性；只有在题目访问量明显增大后，再考虑防缓存击穿。

最终接口（本 Session 先完成前两个）：

```text
GET  /api/v1/study/questions/{questionId}
POST /api/v1/study/questions/{questionId}/attempts
```

AI 生成题目不放在本节一开始实现，而是在固定题目判题闭环稳定后进入 [Session D：AI 出题与解析](session-d-ai-question-generation.md)。这样你能先验证数据库和业务规则，再单独定位模型、检索和 JSON 解析问题。

## 1. 第一个关键概念：选项 ID 与选项编码不是一回事

数据库里的选项可能是这样：

| id | question_id | option_key | content | is_correct |
|---:|---:|---|---|---:|
| 31 | 10 | A | implements | 0 |
| 32 | 10 | B | extends | 1 |
| 33 | 10 | C | inherits | 0 |
| 34 | 10 | D | super | 0 |

`id=32` 是数据库内部主键。它可能因为删除、导入、AUTO_INCREMENT 而变成任意数值，不能暴露为前端协议的一部分。

`option_key=B` 是用户看到并选择的业务编码。它稳定、可读，也适合写在答题历史中。因此请求使用：

```json
{ "options": ["B"] }
```

而不是：

```json
{ "options": [32] }
```

后端收到 `B` 后，会查询这道题的选项，找到 `option_key=B` 的那一行，再根据 `is_correct` 判定。这就是“前端传编码，后端查数据库”的边界。

## 2. 第二个关键概念：单选和多选的协议统一

不要让单选使用字符串、多选使用逗号字符串。统一使用字符串数组：

单选：

```json
{
  "options": ["B"]
}
```

多选：

```json
{
  "options": ["A", "C"]
}
```

这样做有三个好处：

1. 前端复选框天然就是数组，不需要把数组拼成 `A,C`。
2. 后端不需要自己处理逗号和空格，协议歧义更少。
3. 单选和多选走同一套 DTO、Controller 和 Service 方法。

数据库 `study_attempt.answer` 暂时仍是 `VARCHAR(10)`，写入前将数组规范化并排序：

```text
["B"]       -> "B"
["C", "A"]  -> "A,C"
```

排序是为了让 `["A", "C"]` 和 `["C", "A"]` 产生同样的历史值。判题使用集合语义，选项顺序不影响结果。

## 3. 模块与数据库

### 3.1 POM 依赖

`learnhub-application/pom.xml` 必须依赖 `learnhub-study`，否则 Maven 虽然会编译模块，但运行应用时 Controller 不在 classpath 中：

```xml
<dependency>
    <groupId>com.github.comui520</groupId>
    <artifactId>learnhub-study</artifactId>
</dependency>
```

`learnhub-study/pom.xml` 当前使用：

```xml
<dependency>
    <groupId>com.github.comui520</groupId>
    <artifactId>learnhub-common</artifactId>
</dependency>
<dependency>
    <groupId>com.github.comui520</groupId>
    <artifactId>learnhub-knowledge</artifactId>
</dependency>
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

### 3.2 迁移表

如果项目还没有执行过这次迁移，在 `learnhub-study/src/main/resources/db/migration/` 中使用一个没有重复过的版本号。当前项目使用 `V12__init_study_tables.sql`；如果你本地最大版本已经超过 12，就必须改成“当前最大版本 + 1”，不能覆盖已经执行过的迁移。

```sql
CREATE TABLE `study_question`
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id           BIGINT UNSIGNED NOT NULL,
    knowledge_base_id BIGINT UNSIGNED NOT NULL,
    question_type     VARCHAR(30)     NOT NULL DEFAULT 'SINGLE_CHOICE',
    content           VARCHAR(1000)   NOT NULL,
    analysis          VARCHAR(2000)   NULL,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_study_question_user (user_id),
    KEY idx_study_question_kb (knowledge_base_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE `study_question_option`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id BIGINT UNSIGNED NOT NULL,
    option_key  VARCHAR(10)     NOT NULL COMMENT 'A/B/C/D',
    content     VARCHAR(500)    NOT NULL,
    is_correct  TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_question_option (question_id, option_key),
    KEY idx_option_question (question_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE `study_attempt`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    question_id BIGINT UNSIGNED NOT NULL,
    answer      VARCHAR(10)     NOT NULL COMMENT '规范化后的 optionKey，例如 B 或 A,C',
    correct     TINYINT         NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_attempt_user_question (user_id, question_id),
    KEY idx_attempt_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE `study_wrong_question`
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    question_id    BIGINT UNSIGNED NOT NULL,
    wrong_count    INT             NOT NULL DEFAULT 1,
    next_review_at DATETIME        NULL,
    mastered       TINYINT         NOT NULL DEFAULT 0,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wrong_user_question (user_id, question_id),
    KEY idx_wrong_review (user_id, mastered, next_review_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

`study_attempt` 是历史表，所以每次提交都 `INSERT`。`study_wrong_question` 是状态表，所以答错第一次 `INSERT`，再次答错 `UPDATE wrong_count`。

## 4. Entity 与 DTO

### 4.1 题目和选项 Entity

题目实体的关键字段：

```java
@TableName("study_question")
public class StudyQuestion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private String questionType; // SINGLE_CHOICE / MULTIPLE_CHOICE
    private String content;
    private String analysis;
    private LocalDateTime createdAt;
}
```

选项实体中 `isCorrect` 是后端判题数据，不应直接返回给未作答的用户：

```java
@TableName("study_question_option")
public class StudyQuestionOption {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long questionId;
    private String optionKey; // A/B/C/D
    private String content;
    private Integer isCorrect; // 0 / 1
}
```

### 4.2 获取题目的响应 DTO

获取题目时只返回可见信息：

```java
public record StudyOptionResponse(
        String optionKey,
        String content
) {}

public record StudyQuestionView(
        Long id,
        Long knowledgeBaseId,
        String questionType,
        String content,
        List<StudyOptionResponse> options
) {}
```

注意：这里没有 `isCorrect`，否则浏览器开发者工具可以直接看到答案。

### 4.3 提交答案请求 DTO

`SubmitStudyAnswerRequest` 应该改成：

```java
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SubmitStudyAnswerRequest(
        @NotEmpty(message = "Options must not be empty")
        @Size(max = 4, message = "At most four options are allowed")
        List<@NotBlank(message = "Option must not be blank")
                @Size(max = 1, message = "Option must be one character") String> options
) {}
```

这些注解只负责“外形校验”：不能是空数组，最多四项，每项不能是空白，单项长度最多一个字符。它们不能判断 `Z` 是否真的是本题选项，所以 Service 还必须查询数据库后再次校验。

### 4.4 判题结果 DTO

单选和多选都需要数组，因此结果统一为：

```java
public record StudyAnswerResponse(
        Long questionId,
        List<String> selectedOptions,
        Boolean correct,
        List<String> correctOptions,
        String analysis
) {}
```

练习模式可以返回 `correctOptions` 和 `analysis`；如果以后做正式考试，应该另建一个不暴露正确答案的响应 DTO。

## 5. Mapper

MyBatis-Plus 的 `BaseMapper` 已经提供 `selectById`、`selectList`、`insert`、`updateById` 等基础函数，本节使用它们即可：

```java
@Mapper
public interface StudyQuestionMapper extends BaseMapper<StudyQuestion> {}

@Mapper
public interface StudyQuestionOptionMapper
        extends BaseMapper<StudyQuestionOption> {}

@Mapper
public interface StudyAttemptMapper extends BaseMapper<StudyAttempt> {}

@Mapper
public interface StudyWrongQuestionMapper
        extends BaseMapper<StudyWrongQuestion> {}
```

Service 中通过 `LambdaQueryWrapper` 拼条件，避免把用户 ID 交给前端：

```java
StudyQuestion question = questionMapper.selectOne(
        new LambdaQueryWrapper<StudyQuestion>()
                .eq(StudyQuestion::getId, questionId)
                .eq(StudyQuestion::getUserId, userId)
);
```

这两个条件必须同时存在。只按 `questionId` 查，会导致用户 A 只要猜到用户 B 的题目 ID，就有机会读取或提交 B 的题目。

## 6. `CurrentUser`：用户 ID 从哪里来

Controller 不接受 `userId` 参数，Service 也不使用前端传入的用户 ID。当前登录用户由 JWT 过滤器放进 `SecurityContextHolder`，统一通过：

```java
Long userId = currentUser.currentUserId();
```

因此接口 URL 只有 `questionId`，请求体只有 `options`：

```text
POST /api/v1/study/questions/10/attempts
```

```json
{ "options": ["B"] }
```

身份属于认证上下文，答案属于请求数据。两者不要混在一个 DTO 中。

## 7. `submitAnswer` 的完整判题思路

### 7.1 先获取题目和全部选项

```java
Long userId = currentUser.currentUserId();
StudyQuestion question = findOwnedQuestion(questionId, userId);

List<StudyQuestionOption> questionOptions = questionOptionMapper.selectList(
        new LambdaQueryWrapper<StudyQuestionOption>()
                .eq(StudyQuestionOption::getQuestionId, questionId)
                .orderByAsc(StudyQuestionOption::getOptionKey)
);
```

`findOwnedQuestion` 要在查不到时抛 `QUESTION_NOT_FOUND`。这既表示题目不存在，也避免泄露“题目属于另一个用户”这样的信息。

### 7.2 规范化用户提交的编码

```java
private String normalizeOptionKey(String optionKey) {
    return optionKey == null
            ? null
            : optionKey.trim().toUpperCase(Locale.ROOT);
}
```

`Locale.ROOT` 用于稳定的机器字符串转换，不受服务器语言环境影响。然后将用户提交的每一项规范化：

```java
List<String> selectedKeys = submittedOptions.stream()
        .map(this::normalizeOptionKey)
        .toList();
```

接着检查三件事：

```java
Set<String> availableKeys = questionOptions.stream()
        .map(StudyQuestionOption::getOptionKey)
        .filter(Objects::nonNull)
        .map(this::normalizeOptionKey)
        .collect(Collectors.toCollection(LinkedHashSet::new));

boolean hasBlank = selectedKeys.stream()
        .anyMatch(key -> key == null || key.isBlank());
boolean hasDuplicate = new HashSet<>(selectedKeys).size() != selectedKeys.size();
boolean containsUnknownKey = !availableKeys.containsAll(selectedKeys);

if (hasBlank || hasDuplicate || containsUnknownKey) {
    throw new BusinessException(StudyErrorCode.ANSWER_INVALID);
}
```

例如本题只有 `A/B/C/D`，提交 `Z` 必须是 400，而不是被当成一次普通答错；提交 `[A, A]` 也必须拒绝，因为重复选项没有业务意义。

### 7.3 按题型校验数量

```java
if ("SINGLE_CHOICE".equals(question.getQuestionType())
        && selectedKeys.size() != 1) {
    throw new BusinessException(StudyErrorCode.ANSWER_INVALID);
}

if ("MULTIPLE_CHOICE".equals(question.getQuestionType())
        && selectedKeys.isEmpty()) {
    throw new BusinessException(StudyErrorCode.ANSWER_INVALID);
}
```

单选题只能提交一个编码，多选题至少提交一个编码。至于“多选题是否必须至少选择两个”，取决于你的产品规则；当前允许选择一个，因为正确答案集合可能只有一个，数据库数据本身才是最终依据。

如果题型不是这两个值，说明数据库数据不合法，应抛 `IllegalStateException`，不要静默判题。

### 7.4 从数据库得到正确答案集合

```java
List<String> correctKeys = questionOptions.stream()
        .filter(option -> Integer.valueOf(1).equals(option.getIsCorrect()))
        .map(StudyQuestionOption::getOptionKey)
        .filter(Objects::nonNull)
        .map(this::normalizeOptionKey)
        .sorted()
        .toList();
```

不要使用：

```java
option.getIsCorrect() == 1
```

因为 `Integer` 可能是 `null`，自动拆箱时会触发空指针。`Integer.valueOf(1).equals(...)` 对 `null` 更安全。

### 7.5 用集合比较，而不是只比较一个选项

```java
List<String> normalizedSelectedKeys = selectedKeys.stream()
        .sorted()
        .toList();

boolean correct = normalizedSelectedKeys.equals(correctKeys);
```

例子：

```text
正确答案 [B]       用户 [B]       true
正确答案 [A, C]    用户 [C, A]    true
正确答案 [A, C]    用户 [A]       false
正确答案 [A, C]    用户 [A, B]    false
```

这里先排序，所以多选答案的提交顺序不会影响结果。

### 7.6 记录答题历史

```java
String storedAnswer = String.join(",", normalizedSelectedKeys);
LocalDateTime now = LocalDateTime.now();

StudyAttempt attempt = new StudyAttempt();
attempt.setUserId(userId);
attempt.setQuestionId(questionId);
attempt.setAnswer(storedAnswer);
attempt.setCorrect(correct ? 1 : 0);
attempt.setCreatedAt(now);
attemptMapper.insert(attempt);
```

答题历史不能 `updateById`，因为同一道题可以重复练习，每一次提交都有审计和统计价值。

### 7.7 答错时维护错题状态

```java
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
} else {
    wrong.setWrongCount(wrong.getWrongCount() + 1);
    wrong.setMastered(0);
    wrong.setNextReviewAt(now.plusDays(1));
    wrong.setUpdatedAt(now);
    wrongQuestionMapper.updateById(wrong);
}
```

`uk_wrong_user_question (user_id, question_id)` 是数据库最后一道保险，保证同一用户同一道题不会产生两条错题状态。

### 7.8 Service 方法的最终形状

```java
@Transactional
public StudyAnswerResponse submitAnswer(
        Long questionId,
        List<String> submittedOptions
) {
    // 1. currentUser.currentUserId()
    // 2. 查询当前用户拥有的题目
    // 3. 查询题目选项
    // 4. 规范化并校验 selectedOptions
    // 5. 得到 correctOptions，使用集合比较
    // 6. insert study_attempt
    // 7. 答错时 insert/update study_wrong_question
    // 8. 返回一个 StudyAnswerResponse
}
```

加 `@Transactional` 的原因是：答题历史和错题状态属于同一个业务动作。若历史插入成功、错题更新失败，事务回滚后不会留下半完成状态。

## 8. Controller

Controller 只负责 HTTP 参数接收和调用 Service，不负责判题：

```java
@Tag(name = "Study", description = "学习与答题")
@RestController
@RequestMapping("/api/v1/study")
@SecurityRequirement(name = "bearerAuth")
public class StudyController {

    private final StudyService studyService;

    public StudyController(StudyService studyService) {
        this.studyService = studyService;
    }

    @GetMapping("/questions/{questionId}")
    public ApiResponse<StudyQuestionView> getQuestion(
            @PathVariable Long questionId) {
        return ApiResponse.success(
                studyService.getQuestionView(questionId));
    }

    @PostMapping("/questions/{questionId}/attempts")
    public ApiResponse<StudyAnswerResponse> submitAnswer(
            @PathVariable Long questionId,
            @Valid @RequestBody SubmitStudyAnswerRequest request) {
        return ApiResponse.success(
                studyService.submitAnswer(questionId, request.options()));
    }
}
```

注意这里没有 `userId` 参数，也没有 `List<SubmitStudyAnswerRequest>`。请求体是一个 DTO，DTO 里的 `options` 才是选项编码列表。

## 9. 手动验证

### 9.1 插入一题单选题

```sql
INSERT INTO study_question
(user_id, knowledge_base_id, question_type, content, analysis)
VALUES
(1, 1, 'SINGLE_CHOICE', 'Java 中哪个关键字用于继承？',
 'extends 用于类继承');

SET @question_id = LAST_INSERT_ID();

INSERT INTO study_question_option
(question_id, option_key, content, is_correct)
VALUES
(@question_id, 'A', 'implements', 0),
(@question_id, 'B', 'extends', 1),
(@question_id, 'C', 'inherits', 0),
(@question_id, 'D', 'super', 0);
```

### 9.2 插入一题多选题

```sql
INSERT INTO study_question
(user_id, knowledge_base_id, question_type, content, analysis)
VALUES
(1, 1, 'MULTIPLE_CHOICE', '以下哪些是 Java 访问修饰符？',
 'public、protected、private 都是访问修饰符');

SET @multi_id = LAST_INSERT_ID();

INSERT INTO study_question_option
(question_id, option_key, content, is_correct)
VALUES
(@multi_id, 'A', 'public', 1),
(@multi_id, 'B', 'static', 0),
(@multi_id, 'C', 'protected', 1),
(@multi_id, 'D', 'private', 1);
```

### 9.3 PowerShell 7 请求

获取题目：

```powershell
curl.exe -H "Authorization: Bearer <token>" `
  "http://127.0.0.1:8080/api/v1/study/questions/1"
```

提交单选：

```powershell
curl.exe -X POST `
  "http://127.0.0.1:8080/api/v1/study/questions/1/attempts" `
  -H "Authorization: Bearer <token>" `
  -H "Content-Type: application/json" `
  -d '{"options":["B"]}'
```

提交多选，顺序故意写成 `C,A`：

```powershell
curl.exe -X POST `
  "http://127.0.0.1:8080/api/v1/study/questions/2/attempts" `
  -H "Authorization: Bearer <token>" `
  -H "Content-Type: application/json" `
  -d '{"options":["C","A"]}'
```

服务端应该把它规范化为 `A,C`，并判定为正确（前提是正确答案就是 A、C）。

检查数据库：

```sql
SELECT * FROM study_attempt ORDER BY id DESC;
SELECT * FROM study_wrong_question ORDER BY id DESC;
```

### 9.4 必须测试的错误输入

```text
{"options": []}          -> 400，不能为空
{"options": ["Z"]}      -> 400，不是本题选项
{"options": ["A","A"]} -> 400，重复选项
单选提交 ["A","B"]       -> 400，单选只能一个
多选提交不存在的编码       -> 400
```

## 10. 常见错误与排查方向

### `options` 类型不匹配

如果 DTO 是 `List<String>`，请求必须是数组：`{"options":["B"]}`。写成 `{"options":"B"}` 会在 JSON 反序列化阶段失败。

### 把数据库 ID 当成答案

不要提交 `32`。即使数据库当前 B 的 ID 是 32，下一套数据、另一道题或者重新导入后都可能不同。永远提交 `B`。

### 判题条件写反

错误写法：

```java
if (allows.containsAll(selectedKeys)) {
    throw ...;
}
```

正确思路是“不满足条件时抛异常”，例如：

```java
if (!availableKeys.containsAll(selectedKeys)) {
    throw new BusinessException(StudyErrorCode.ANSWER_INVALID);
}
```

### 用户可以访问别人的题目

题目查询必须同时带 `id` 和 `user_id`。不能只写 `.eq(StudyQuestion::getId, questionId)`。

### 多选只比较第一个正确答案

多选必须得到 `correctKeys` 集合，再与 `selectedKeys` 排序后的集合整体比较，不能使用 `findFirst()`。

### `study_wrong_question` 出现重复行

检查唯一索引 `uk_wrong_user_question` 是否已执行，并确认查询条件同时使用当前 `userId` 和 `questionId`。

## 11. 本节完成标准

- [ ] DTO 使用 `List<String> options`，而不是请求 DTO 列表或逗号字符串。
- [ ] 单选和多选都能使用选项编码提交。
- [ ] 后端拒绝不存在、重复、空白的选项编码。
- [ ] 后端按 `questionType` 校验单选/多选数量。
- [ ] 判题使用正确选项编码集合比较，顺序不影响多选结果。
- [ ] 用户 ID 只从 `CurrentUser.currentUserId()` 获取。
- [ ] 每次提交都有一条 `study_attempt`。
- [ ] 答错时 `study_wrong_question` 首次插入、再次答错递增。
- [ ] `is_correct` 不出现在获取题目的响应中。

错题复习和统计功能建立在这个闭环之上；知识库驱动的 AI 出题请继续阅读 [Session D：AI 出题与解析](session-d-ai-question-generation.md)。
