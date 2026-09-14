package com.github.comui520.learnhub.ai.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "learnhub.redis.ai")
public class AiRedisProperties {
    private String aiChatKey;
}
