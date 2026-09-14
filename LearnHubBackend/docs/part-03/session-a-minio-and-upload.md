# Session A：MinIO 接入 + 建表 + 知识库创建 + 文件上传

> 目标：把 MinIO 接进项目，建好知识库/文档/任务三张表，实现“创建知识库”和“上传文档”两个接口。
>
> 档位：🧑‍🏫 我带。预计 4～5 小时。

## 0. 今天到底要学会什么

1. 为什么文件要存对象存储，而不是本地磁盘。
2. MinIO 的 bucket、object 是什么，Java SDK 怎么上传。
3. Multipart 文件上传在 Spring 里怎么写，`multipart` 限制怎么配。
4. SHA-256 文件去重怎么实现。
5. 文档状态机怎么“先设计、后实现”。

---

## 1. 先建立直觉：为什么不能把文件存在本地磁盘

上传的文件如果写进 `D:/uploads/xxx.pdf`：

- 应用部署多实例时，文件只在这台机器的磁盘上，别的实例读不到（负载均衡一转发就 404）。
- 磁盘满了要手动清理，没有统一的生命周期管理。
- 备份、扩容、迁移都很难。

对象存储（MinIO、AWS S3）把这些问题抽象掉：文件变成“对象”，放进“桶”，通过 HTTP API 读写，天然支持多实例共享、按需扩容。

**MinIO 是什么**：一个开源的、兼容 S3 协议的对象存储服务器，`docker compose` 里已经跑着它（`localhost:9000` API、`localhost:9001` 控制台）。

---

## 2. 先认识关键概念

### 2.1 bucket 和 object

- **bucket（桶）**：对象的容器，类似“顶层文件夹”，全局唯一，需要提前创建。
- **object（对象）**：一个文件 + 元数据，object name 可以带路径风格，比如 `documents/{sha256}/report.pdf`。

### 2.2 MinIO Java SDK 的核心 API

```java
MinioClient client = MinioClient.builder()
        .endpoint("http://localhost:9000")
        .credentials("learnhub", "123456789")   // accessKey / secretKey
        .build();

client.bucketExists(BucketExistsArgs.builder().bucket("learnhub-docs").build());
client.makeBucket(MakeBucketArgs.builder().bucket("learnhub-docs").build());

client.putObject(PutObjectArgs.builder()
        .bucket("learnhub-docs")
        .object("documents/xxx/report.pdf")
        .stream(inputStream, fileSize, -1)      // 流、大小、分片（-1=自动）
        .contentType("application/pdf")
        .build());
```

### 2.3 Multipart 上传

Spring MVC 里，文件用 `MultipartFile` 接收：

```java
@PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ApiResponse<DocumentResponse> upload(@RequestParam("file") MultipartFile file) { ... }
```

`file.getOriginalFilename()`（客户端文件名，不可信）、`file.getSize()`、`file.getInputStream()`、`file.getContentType()`。

⚠️ Spring Boot 默认最大上传 **1MB**，超出直接报错。文档要放宽到比如 20MB：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 20MB
```

### 2.4 SHA-256 去重

同一个文件（内容相同）算出的 SHA-256 一定相同，所以它是“内容指纹”：

```java
MessageDigest digest = MessageDigest.getInstance("SHA-256");
byte[] hash = digest.digest(inputStream.readAllBytes());
String sha256 = HexFormat.of().formatHex(hash);   // Java 17+ 自带 HexFormat
```

去重策略：`document` 表建 `UNIQUE KEY uk_kb_sha256 (knowledge_base_id, sha256)`——**同一知识库内**不能重复上传同一文件；同一文件传到**不同知识库**是允许的（各一条记录）。MinIO 对象名仍用 sha256，存储天然只存一份。上传前先查一次给出友好 409；唯一索引是并发下的最后防线（Part 2 学过同一招）。

### 2.5 文档状态机

先设计，后实现。文档的生命周期：

```text
UPLOADED -> PARSING -> EMBEDDING -> COMPLETED
                       \-> FAILED（任一步失败）
```

Part 3 只走到 **UPLOADED**（解析是 Part 4/5 的事），但表里先存完整枚举、代码里先定义 `DocumentStatus`，后面直接推进。

---

## 3. 跟着做一遍

### Step 1：确认 MinIO 在跑

```powershell
docker compose ps
```

`learnhub-minio-1` 应该 Up。打开 `http://localhost:9001`（控制台），用 `.env` 里的 `MINIO_ROOT_USER=learnhub` / `MINIO_ROOT_PASSWORD=123456789` 登录，确认能进去。

### Step 2：把 infrastructure 依赖加回 knowledge 模块

编辑 `learnhub-knowledge/pom.xml`，在 `learnhub-common` 依赖下面加：

```xml
<dependency>
    <groupId>com.github.comui520</groupId>
    <artifactId>learnhub-infrastructure</artifactId>
</dependency>
```

> 这就是 Part 1 去掉它时说的“按需接入”：现在需要 MinIO 了，加回来。infrastructure 里还带着 Redis/AMQP/Qdrant 的自动配置，它们大多是懒连接，大概率不影响启动；**如果启动日志出现连不上 Redis/RabbitMQ/Qdrant 的报错**，在 `application-dev.yml` 里临时排除，等用到时再放开：
>
> ```yaml
> spring:
>   autoconfigure:
>     exclude:
>       - org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration
> ```

顺便检查 application 模块已依赖 knowledge（如果还没加，加 `<artifactId>learnhub-knowledge</artifactId>` 依赖）。

### Step 3：MinIO 配置

在 `learnhub-infrastructure` 模块新建 `minio` 包，两个类：

```text
learnhub-infrastructure/src/main/java/com/github/comui520/learnhub/infrastructure/minio/MinioProperties.java
```

```java
package com.github.comui520.learnhub.infrastructure.minio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "learnhub.minio")
public class MinioProperties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    // 四个字段的 getter / setter
}
```

```text
learnhub-infrastructure/src/main/java/com/github/comui520/learnhub/infrastructure/minio/MinioConfig.java
```

```java
package com.github.comui520.learnhub.infrastructure.minio;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /** 启动时确保 bucket 存在（幂等） */
    @Bean
    public ApplicationRunner minioBucketInitializer(MinioClient minioClient, MinioProperties properties) {
        return args -> {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
                log.info("MinIO bucket created: {}", properties.getBucket());
            }
        };
    }
}
```

在 `application-dev.yml` 末尾加：

```yaml
learnhub:
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ROOT_USER:learnhub}
    secret-key: ${MINIO_ROOT_PASSWORD:123456789}
    bucket: ${MINIO_BUCKET:learnhub-docs}
```

### Step 4：建表（V5 迁移）

先确认当前最高版本：

```powershell
docker compose exec mysql mysql -ulearnhub -p1234 learnhub -e "SELECT MAX(version) FROM flyway_schema_history;"
```

假设最高是 V4（如果你 Session E 做了审计表就是 V5，那就顺延），创建：

```text
learnhub-knowledge/src/main/resources/db/migration/V5__init_knowledge_base_tables.sql
```

```sql
CREATE TABLE `knowledge_base`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
    name        VARCHAR(50)     NOT NULL COMMENT '知识库名称',
    description VARCHAR(255)    NULL COMMENT '描述',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_name (user_id, name),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库表';

CREATE TABLE `document`
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    knowledge_base_id BIGINT UNSIGNED NOT NULL COMMENT '所属知识库 ID',
    user_id           BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
    file_name         VARCHAR(255)    NOT NULL COMMENT '原始文件名',
    file_size         BIGINT          NOT NULL COMMENT '文件大小（字节）',
    content_type      VARCHAR(100)    NULL COMMENT 'MIME 类型',
    sha256            CHAR(64)        NOT NULL COMMENT '文件 SHA-256',
    object_name       VARCHAR(500)    NOT NULL COMMENT 'MinIO 对象名',
    status            VARCHAR(20)     NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PARSING/EMBEDDING/COMPLETED/FAILED',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_sha256 (knowledge_base_id, sha256),
    KEY idx_knowledge_base_id (knowledge_base_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='文档表';

CREATE TABLE `document_task`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    document_id BIGINT UNSIGNED NOT NULL COMMENT '文档 ID',
    type        VARCHAR(30)     NOT NULL DEFAULT 'PARSE' COMMENT '任务类型',
    status      VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    retry_count INT             NOT NULL DEFAULT 0 COMMENT '重试次数',
    last_error  VARCHAR(500)    NULL COMMENT '最近一次错误',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_document_id (document_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='文档解析任务表';
```

看懂三个设计点：

- `uk_user_name`：同一用户不能建两个同名知识库。
- `uk_kb_sha256`：同一知识库不能重复上传同一文件；跨知识库允许（MinIO 对象仍按 sha256 只存一份，删除时要注意引用，Session C 讲）。
- `document_task` 现在只建表不写代码，Part 4 用。

### Step 5：实体和 Mapper

`learnhub-knowledge` 模块新建三个实体（用 `@Getter @Setter` + `@TableName`，照抄 Part 2 的 User 风格）：`KnowledgeBase`、`Document`、`DocumentTask`，字段对应表。

两个 Mapper（关键 SQL 手写）：

```java
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /** 数据隔离：按 ID 查，且必须属于当前用户 */
    @Select("SELECT * FROM `knowledge_base` WHERE id = #{id} AND user_id = #{userId}")
    KnowledgeBase findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
```

```java
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    @Select("SELECT * FROM `document` WHERE knowledge_base_id = #{knowledgeBaseId} AND sha256 = #{sha256}")
    Document findByKnowledgeBaseIdAndSha256(@Param("knowledgeBaseId") Long knowledgeBaseId, @Param("sha256") String sha256);

    @Select("SELECT * FROM `document` WHERE id = #{id} AND user_id = #{userId}")
    Document findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
```

### Step 6：错误码和 DTO

`KnowledgeErrorCode`（放 knowledge 模块）：

```java
public enum KnowledgeErrorCode implements ErrorCode {
    KNOWLEDGE_BASE_NOT_FOUND("KB_ERROR_0404", "Knowledge base not found", 404),
    KNOWLEDGE_BASE_NAME_EXISTS("KB_ERROR_0409", "Knowledge base name already exists", 409),
    DUPLICATE_DOCUMENT("DOC_ERROR_0409", "Document already exists", 409),
    DOCUMENT_NOT_FOUND("DOC_ERROR_0404", "Document not found", 404),
    ;
    // 枚举实现同 UserErrorCode，照抄
}
```

DTO（record，风格照抄 Part 2）：

- `CreateKnowledgeBaseRequest(String name, String description)`，name 加 `@NotBlank` + `@Size(max = 50)`。
- `KnowledgeBaseResponse(Long id, String name, String description, LocalDateTime createdAt)`。
- `DocumentResponse(Long id, Long knowledgeBaseId, String fileName, Long fileSize, String contentType, String sha256, String status, LocalDateTime createdAt)`。

### Step 7：创建知识库（第一个 CRUD，我带）

`KnowledgeBaseService`：

```java
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    public KnowledgeBaseResponse create(Long userId, CreateKnowledgeBaseRequest request) {
        // 同名检查给友好报错；uk_user_name 是并发兜底
        if (knowledgeBaseMapper.existsByUserIdAndName(userId, request.name())) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NAME_EXISTS);
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setUserId(userId);
        kb.setName(request.name());
        kb.setDescription(request.description());
        knowledgeBaseMapper.insert(kb);

        return new KnowledgeBaseResponse(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
    }
}
```

需要在 `KnowledgeBaseMapper` 加一个查询（自己补：`existsByUserIdAndName`，用 `SELECT COUNT(*) ... WHERE user_id=#{userId} AND name=#{name}` 返回 `boolean` 或 `Integer`）。

`KnowledgeBaseController`（注意从 SecurityContext 取 userId，和 Part 2 的 `me()` 一样）：

```java
@Tag(name = "KnowledgeBase", description = "知识库")
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    // 构造器注入

    @Operation(summary = "创建知识库")
    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        Long userId = currentUserId();
        return ApiResponse.success(knowledgeBaseService.create(userId, request));
    }

    private Long currentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

### Step 8：文件上传（本 Part 的核心）

`DocumentService.upload`：

```java
@Service
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    // 构造器注入

    @Transactional
    public DocumentResponse upload(Long userId, Long knowledgeBaseId, MultipartFile file) throws Exception {
        // ① 知识库必须属于当前用户（数据隔离第一关）
        KnowledgeBase kb = knowledgeBaseMapper.findByIdAndUserId(knowledgeBaseId, userId);
        if (kb == null) {
            throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // ② 算 SHA-256（内容指纹）
        String sha256 = sha256Hex(file.getInputStream());

        // ③ 同知识库重复文件：友好 409（uk_kb_sha256 是并发兜底）
        if (documentMapper.findByKnowledgeBaseIdAndSha256(knowledgeBaseId, sha256) != null) {
            throw new BusinessException(KnowledgeErrorCode.DUPLICATE_DOCUMENT);
        }

        // ④ 上传 MinIO：对象名用 sha256 保证唯一
        String objectName = "documents/" + sha256 + "/" + file.getOriginalFilename();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioProperties.getBucket())
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());

        // ⑤ 建记录（状态 UPLOADED）
        Document document = new Document();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setUserId(userId);
        document.setFileName(file.getOriginalFilename());
        document.setFileSize(file.getSize());
        document.setContentType(file.getContentType());
        document.setSha256(sha256);
        document.setObjectName(objectName);
        document.setStatus(DocumentStatus.UPLOADED.name());
        documentMapper.insert(document);

        return toResponse(document);
    }

    private String sha256Hex(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(in.readAllBytes());
        return HexFormat.of().formatHex(hash);
    }
}
```

需要你补：`DocumentStatus` 枚举（UPLOADED/PARSING/EMBEDDING/COMPLETED/FAILED）、`toResponse` 方法、`DocumentController` 的 upload 接口（`POST /api/v1/knowledge-bases/{id}/documents`，`@RequestParam("file") MultipartFile`，记得 `consumes = MULTIPART_FORM_DATA_VALUE`）。

### Step 9：验证

重启应用，先登录拿 token，然后：

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/knowledge-bases" -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -d "{\"name\":\"我的资料\",\"description\":\"学习资料\"}"

curl.exe -i -X POST "http://localhost:8080/api/v1/knowledge-bases/1/documents" -H "Authorization: Bearer <token>" -F "file=@D:\somefile.pdf"
```

期望：知识库创建 200；上传 200，响应里有 sha256 和 status=UPLOADED。再去 MinIO 控制台（9001）确认 `learnhub-docs` 桶里出现了对象，MySQL 里 `document` 表有记录。

---

## 4. 主动制造错误（每个做完恢复）

**错误 A：超 1MB 的文件**

先把 `max-file-size` 那两行注释掉（恢复默认 1MB），传一个 2MB 的文件，观察报错（`MaxUploadSizeExceededException` → 现在是 500，后面会改成友好 4xx）。恢复配置。

**错误 B：传别人的知识库**

用用户 A 创建知识库，再用用户 B 的 token 往 A 的知识库上传，期望 404（`KNOWLEDGE_BASE_NOT_FOUND`）——亲眼看到“SQL 带 user_id 条件”的效果。

**错误 C：重复上传同一文件**

同一个文件传两次，第二次期望 409 `DUPLICATE_DOCUMENT`。

**错误 D：MinIO 停掉**

`docker compose stop minio`，再上传，观察异常（连接拒绝）。恢复 `docker compose start minio`。

---

## 5. 复盘题

1. 为什么文件要存对象存储而不是本地磁盘？多实例部署时差异在哪？
2. bucket 和 object 是什么？object name 为什么用 sha256 开头？
3. 上传流程里“查重 → 传 MinIO → 建记录”三步，第 4 步失败会怎样？`@Transactional` 能回滚 MinIO 的上传吗？（提示：不能，这是分布式一致性问题，Session C 会讲处理）
4. 为什么文件大小、类型不能信任客户端的值？
5. `uk_kb_sha256` 唯一索引和 Service 查重是什么关系？（Part 2 答过一遍，现在再答）

完成并验证后，进入 [Session B](session-b-knowledge-base-crud.md)：知识库剩余 CRUD——这次换你主导。
