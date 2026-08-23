package com.github.comui520.learnhub.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.knowledge.KnowledgeErrorCode;
import com.github.comui520.learnhub.knowledge.config.reader.MarkdownReaderConfig;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import com.github.comui520.learnhub.knowledge.mapper.DocumentFileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class DocumentParseService {

    private final MarkdownReaderConfig markdownReaderConfig;
    private final DocumentFileMapper documentFileMapper;

    public DocumentParseService(
            MarkdownReaderConfig markdownReaderConfig,
            DocumentFileMapper documentFileMapper) {
        this.markdownReaderConfig = markdownReaderConfig;
        this.documentFileMapper = documentFileMapper;
    }

    public List<Document> parseAndChunk(
            Long fileId,
            Long userId,
            String fileName,
            InputStream inputStream
    ) throws Exception {
        Resource resource = new InputStreamResource(inputStream);
        List<Document> documents = readDocuments(fileName, resource);

        documents.forEach(
                doc -> {
                    doc.getMetadata().put("fileId", fileId);
                    doc.getMetadata().put("userId", userId);
                    doc.getMetadata().put("fileName", fileName);
                }
        );

        TextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)       // 中文建议600字符左右
                // 补充中文标点到分隔符列表，优先级在换行之后、空格之前
                .build();

        List<Document> chunks = splitter.split(documents);

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunks.set(i, chunk.mutate()
                    .id(UUID.nameUUIDFromBytes((fileId + "-" + i).getBytes()).toString())
                    .metadata("chunkIndex", i)
                    .build());
        }

        log.info("parse done: fileId={}, chunks={}", fileId, chunks.size());

        DocumentFile file = documentFileMapper.selectById(fileId);
        if (file == null) throw new BusinessException(KnowledgeErrorCode.DOCUMENT_NOT_FOUND);

        file.setChunkCount(chunks.size());

        documentFileMapper.updateById(file);
        return chunks;
    }

    public List<Document> readDocuments(String fileName, Resource resource) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return new PagePdfDocumentReader(resource).get();
        }
        if (lower.endsWith(".md")) {
            return new MarkdownDocumentReader(resource, markdownReaderConfig.markdownDocumentReaderConfig()).get();
        }
        return new TikaDocumentReader(resource).get();
    }
}
