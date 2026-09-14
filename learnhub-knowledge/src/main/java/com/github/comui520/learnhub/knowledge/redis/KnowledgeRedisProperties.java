package com.github.comui520.learnhub.knowledge.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "learnhub.redis.kb")
public class KnowledgeRedisProperties {
    private String kbDetailKey;
    private String kbDfKey;
    private String kbDfKbKey;
}
