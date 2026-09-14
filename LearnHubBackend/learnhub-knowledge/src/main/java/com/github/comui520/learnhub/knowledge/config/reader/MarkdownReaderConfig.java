package com.github.comui520.learnhub.knowledge.config.reader;

import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
public class MarkdownReaderConfig {

    @Bean
    public MarkdownDocumentReaderConfig markdownDocumentReaderConfig() {
        return MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("source", "markdown_docs")
                        .build();
    }
}
