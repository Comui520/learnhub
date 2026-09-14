# Session A：真实解析 PDF / Word / Markdown + 切分

> 目标：把 Part 4 消费者里的 `Thread.sleep(1000)` 换成真的——从 MinIO 下载文件 → 读成文本 → 切成 chunk。做完这一步，日志里能看到“解析成功，共 N 个 chunk”。
> 档位：🧑‍🏫 我带（代码全给，逐段解释）。预计 3～4 小时。
>
> 前置：这篇**不需要 API Key**（还没调用任何模型），可以放心做。

## 0. 本 Session 完成时的样子

```text
绑定文件 → 消息进队 → 消费者收到
  → 从 MinIO 下载流 → DocumentReader 读文本 → TokenTextSplitter 切分
  → 日志：parse done: fileId=1, chunks=12
  → document_file.status = COMPLETED
```

向量化（Embedding）是 Session B 的事，今天只到“切好块”。

## 1. 三个新概念（primer 的落地版）

- **DocumentReader**：把“文件”读成“文本”。按类型选：
  - PDF → `PagePdfDocumentReader`（按页读）
  - Markdown → `MarkdownDocumentReader`
  - Word / 其他 → `TikaDocumentReader`（万能兜底，docx/xlsx/pptx 都行）
- **`Document`（Spring AI 的）**：一个“文本 + 元数据”的对象。`getText()` 拿正文，`getMetadata()` 拿 key-value 元数据。
- **`TokenTextSplitter`**：把长文本按 token 数切块，`chunkSize` 每块多大、`chunkOverlap` 相邻块重叠多少。重叠是为了防止一句话正好被切成两半。

## 2. Step 1：确认依赖

`learnhub-knowledge/pom.xml` 里已经有三个 reader 依赖（Part 1 预置的），`TokenTextSplitter` 在 Spring AI 核心包里，不用加：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tika-document-reader</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pdf-document-reader</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-markdown-document-reader</artifactId>
</dependency>
```

## 3. Step 2：新建 DocumentParseService

新建 `learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/service/DocumentParseService.java`：

```java
package com.github.comui520.learnhub.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
public class DocumentParseService {

    /** 把文件流解析成文本并切分成 chunk，返回带元数据的 Document 列表 */
    public List<Document> parseAndChunk(
            Long fileId,
            Long userId,
            String fileName,
            InputStream inputStream
    ) throws Exception {
        // ① 文件流包装成 Spring 的 Resource
        Resource resource = new InputStreamResource(inputStream);

        // ② 按类型读成文本（每页/每段是一个 Document）
        List<Document> documents = readDocuments(fileName, resource);

        // ③ 给每个 Document 打上来源元数据（溯源、清理、隔离都用它）
        documents.forEach(doc -> {
            doc.getMetadata().put("fileId", fileId);
            doc.getMetadata().put("userId", userId);
            doc.getMetadata().put("fileName", fileName);
        });

        // ④ 切分
        TextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withChunkOverlap(50)
                .build();
        List<Document> chunks = splitter.split(documents);

        // ⑤ 给每块加序号（溯源时告诉用户“第几块”）
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).getMetadata().put("chunkIndex", (long) i); // 用 Long：Qdrant 读回是 Long，Session C 强转 Long（见 primer 4.6）
        }

        log.info("parse done: fileId={}, chunks={}", fileId, chunks.size());
        return chunks;
    }

    private List<Document> readDocuments(String fileName, Resource resource) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return new PagePdfDocumentReader(resource).get();
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return new MarkdownDocumentReader(resource).get();
        }
        // docx / xlsx / pptx / txt / 未知类型：Tika 兜底
        return new TikaDocumentReader(resource).get();
    }
}
```

逐段理解：

- `new InputStreamResource(inputStream)`：MinIO 返回的是 `InputStream`，Spring AI 的 Reader 要的是 `Resource`，这里包一层。
- `readDocuments` 按扩展名选 Reader；`.get()` 返回 `List<Document>`。**PDF 按页**（一页一个 Document），Tika 按段。
- 元数据为什么要打 `fileId` / `userId` / `fileName`：
  - `fileId`：Session B 写向量时当主键，删除文件时清理向量；
  - `userId`：数据隔离的兜底；
  - `fileName`：回答时告诉用户“来自哪个文件”。
  - **故意不存 knowledgeBaseId**：一个文件可能绑定多个知识库（Part 3 的多对多），存单个库会让其他库检索不到。知识库归属在 MySQL 关联表里，检索时再查（Session C 讲）。
- `TokenTextSplitter.builder().withChunkSize(500).withChunkOverlap(50)`：每块约 500 token，相邻重叠 50。这两个数之后可以自己调（primer 说过没有标准答案）。
- `splitter.split(documents)`：把“每页一个大文档”再切成“每块一个小文档”，返回值就是最终 chunk。
- `chunkIndex` 序号：方便溯源和调试。

> 面试点：切分为什么要有 overlap？因为句子可能被从中间截断，重叠让切分点附近的上下文不会丢失。

## 4. Step 3：改消费者，把假解析换成真的

打开 `DocumentParseConsumer`，注入两个新依赖，把 `parseContent()` 换掉：

```java
private final DocumentParseService documentParseService;
private final MinioClient minioClient;
private final MinioProperties minioProperties;

public DocumentParseConsumer(
        DocumentFileMapper documentFileMapper,
        DocumentTaskMapper documentTaskMapper,
        DocumentParseService documentParseService,
        MinioClient minioClient,
        MinioProperties minioProperties
) {
    this.documentFileMapper = documentFileMapper;
    this.documentTaskMapper = documentTaskMapper;
    this.documentParseService = documentParseService;
    this.minioClient = minioClient;
    this.minioProperties = minioProperties;
}
```

`onParse` 主流程不变，只是把 `parseContent();` 这行改成：

```java
DocumentFile file = documentFileMapper.selectById(fileId);
if (file == null) {
    throw new IllegalStateException("file not found: " + fileId);
}
parseContent(file);
```

原来的 `private void parseContent()` 替换成：

```java
private void parseContent(DocumentFile file) throws Exception {
    InputStream inputStream = minioClient.getObject(
            GetObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(file.getObjectName())
                    .build()
    );
    documentParseService.parseAndChunk(
            file.getId(),
            file.getUserId(),
            file.getFileName(),
            inputStream
    );
}
```

要点：

- 从 MinIO 拉流用 `file.getObjectName()`（Part 3 的教训，别再用文件名当对象名）。
- `file` 已经在 TODO 2 里查过了，这里再查一次是为了传给 `parseContent`（也可以直接把 TODO 2 查出的 `file` 传下来，看你代码结构）。
- 切分结果先只打日志，Session B 用它做向量化。

## 5. Step 4：验证

1. 重启应用。
2. 上传一个 Markdown 或 PDF（纯文本最好，比如 2000 字的 md），绑定到知识库。
3. 看日志：

```text
parse done: fileId=1, chunks=12
```

4. 查库：`SELECT id, status FROM document_file WHERE id=1;` → `COMPLETED`。
5. 换一个 PDF 试：如果 PDF 扫描版（图片）会读到空文本——这是正常的，Tika 不认图片文字，**别慌，那是 OCR 的领域**，本项目不涉及。

## 6. 主动制造错误

**错误 A：传一个空文件 / 全是图片的 PDF**：`parseAndChunk` 返回空列表或 0 chunk。观察日志，思考：解析“成功”但内容为空，应该算成功吗？（面试点：空内容应该标记 FAILED 还是 COMPLETED？合理做法是 FAILED + lastError="no text extracted"。你想改就改，不强制。）

**错误 B：文件名大小写**：把 `.PDF` 传上去，`toLowerCase()` 已经兜住了；如果你不写 `toLowerCase()`，`.PDF` 会走 Tika，也能解析——只是路径不同。体会“永远不要信任客户端文件名”的另一层含义。

## 7. 复盘题

1. 为什么切分而不是整体喂给模型？
2. chunk 元数据里为什么存 fileId / userId / fileName，但**不存** knowledgeBaseId？
3. `TokenTextSplitter` 的 chunkSize 和 chunkOverlap 分别控制什么？
4. 解析到空文本时，你打算怎么处理？
5. 消费者抛异常后，消息会怎样？（复习 Part 4：重试 → 死信）

完成后进入 [Session B](session-b-embedding-and-vector-store.md)：把切好的块向量化，写进 Qdrant。
