package com.github.comui520.learnhub.ai.utils;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VectorUtil {
    public String buildContext(List<Document> hits) {
        StringBuilder contextBuilder = new StringBuilder();
        for (Document hit : hits) {
            String fileName = String.valueOf(hit.getMetadata().get("fileName"));
            Object rawIndex = hit.getMetadata().get("chunkIndex");
            if (rawIndex == null) {
                rawIndex = hit.getMetadata().get("chunk_index");
            }
            long chunkIndex = toChunkIndex(rawIndex);
            contextBuilder.append("【来源：")
                    .append(fileName)
                    .append(" 第")
                    .append(chunkIndex)
                    .append("块】\n")
                    .append(hit.getText())
                    .append("\n\n");
        }
        return contextBuilder.toString();
    }

    private long toChunkIndex(Object rawIndex) {
        if (rawIndex instanceof Number number) {
            return number.longValue();
        }
        if (rawIndex == null) {
            return -1L;
        }
        try {
            return Long.parseLong(String.valueOf(rawIndex));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
