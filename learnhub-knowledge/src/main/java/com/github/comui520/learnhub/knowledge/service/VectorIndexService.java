package com.github.comui520.learnhub.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class VectorIndexService {

    private final VectorStore vectorStore;

    public VectorIndexService(
            VectorStore vectorStore
    ) {
        this.vectorStore = vectorStore;
    }

    public void addChunks(List<Document> chunks) {
        if (chunks.isEmpty()) return;
        vectorStore.add(chunks);
        log.info("indexed {} chunks into vector store", chunks.size());
    }

    public void deleteByFileId(Long fileId, Integer chunkCount) {
        if (chunkCount <= 0) {
            return;
        }

        List<String> ids = new ArrayList<>();

        for (int i = 0; i < chunkCount; i++) {
            ids.add(UUID.nameUUIDFromBytes((fileId + "-" + i).getBytes()).toString());
        }

        vectorStore.delete(ids);

    }
}
