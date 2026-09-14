package com.github.comui520.learnhub.user.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "learnhub.redis.user")
public class UserRedisProperties {
    private String usLgAtKey;
    private String usLgFlKey;
}
